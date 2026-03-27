package com.decisionmesh.infrastructure.llm.anthropic;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.infrastructure.llm.LlmAdapter;
import com.decisionmesh.infrastructure.llm.LlmAdapterException;
import com.decisionmesh.infrastructure.llm.LlmTimeoutException;
import com.decisionmesh.infrastructure.llm.prompt.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic Messages API adapter.
 *
 * Supported models:  claude-3-5-sonnet, claude-3-5-haiku, claude-3-opus, claude-3-haiku
 * Wire format:       POST /v1/messages
 * Auth:              x-api-key + anthropic-version headers
 * Cost model:        input_tokens × input_price + output_tokens × output_price
 */
@ApplicationScoped
public class AnthropicLlmAdapter implements LlmAdapter {

    private static final String PROVIDER         = "ANTHROPIC";
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION      = "2023-06-01";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "llm.anthropic.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.anthropic.default-model",      defaultValue = "claude-3-5-sonnet-20241022")
    String defaultModel;

    @ConfigProperty(name = "llm.anthropic.default-timeout-ms", defaultValue = "30000")
    int defaultTimeoutMs;

    @ConfigProperty(name = "llm.anthropic.claude-3-5-sonnet.input-cost-per-1k",  defaultValue = "0.003")
    BigDecimal sonnetInputCostPer1k;

    @ConfigProperty(name = "llm.anthropic.claude-3-5-sonnet.output-cost-per-1k", defaultValue = "0.015")
    BigDecimal sonnetOutputCostPer1k;

    @ConfigProperty(name = "llm.anthropic.claude-3-5-haiku.input-cost-per-1k",   defaultValue = "0.001")
    BigDecimal haikuInputCostPer1k;

    @ConfigProperty(name = "llm.anthropic.claude-3-5-haiku.output-cost-per-1k",  defaultValue = "0.005")
    BigDecimal haikuOutputCostPer1k;

    @ConfigProperty(name = "llm.anthropic.claude-3-opus.input-cost-per-1k",      defaultValue = "0.015")
    BigDecimal opusInputCostPer1k;

    @ConfigProperty(name = "llm.anthropic.claude-3-opus.output-cost-per-1k",     defaultValue = "0.075")
    BigDecimal opusOutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public AnthropicLlmAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── LlmAdapter ────────────────────────────────────────────────────────────

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Uni<ExecutionRecord> execute(Intent intent, PlanStep step, int attempt) {
        Instant startedAt = Instant.now();

        // configSnapshot is now Map<String, Object> — use PlanStep convenience getters
        String model    = step.getConfigString("model",    defaultModel);
        int maxTokens   = step.getConfigInt("max_tokens",  1024);
        String endpoint = step.getConfigString("endpoint", DEFAULT_ENDPOINT);
        long timeoutMs  = step.getConfigLong("timeout_ms", (long) defaultTimeoutMs);

        // Build request body using Jackson ObjectNode
        ArrayNode messages = PromptBuilder.buildMessages(intent);

        ObjectNode requestBody = MAPPER.createObjectNode()
                .put("model",      model)
                .put("max_tokens", maxTokens);
        requestBody.set("messages", messages);

        String requestBodyStr;
        try {
            requestBodyStr = MAPPER.writeValueAsString(requestBody);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new LlmAdapterException("ADAPTER_ERROR",
                            "Failed to serialize Anthropic request: " + e.getMessage(),
                            PROVIDER, model, attempt, 0L));
        }

        Log.debugf("Anthropic request: model=%s, intent=%s, attempt=%d",
                model, intent.getId(), attempt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type",      "application/json")
                .header("x-api-key",         apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();

        CompletableFuture<ExecutionRecord> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(response ->
                                parseResponse(response, intent, step, attempt, model, startedAt))
                        .exceptionally(ex -> {
                            throw mapException(ex, model, attempt, startedAt);
                        });

        return Uni.createFrom().completionStage(future);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExecutionRecord parseResponse(
            HttpResponse<String> response,
            Intent intent, PlanStep step, int attempt,
            String model, Instant startedAt) {

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response.statusCode() == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "Anthropic rate limit exceeded: " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() == 529) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "Anthropic overloaded (529): " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "Anthropic HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }

        // Parse response body with Jackson
        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "Anthropic returned unparseable JSON: " + e.getMessage(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode content = body.get("content");
        JsonNode usage   = body.get("usage");

        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "Anthropic returned empty content array",
                    PROVIDER, model, attempt, latencyMs);
        }

        // Find first text block in content array
        String outputText = "";
        for (JsonNode block : content) {
            if ("text".equals(safeText(block, "type"))) {
                outputText = safeText(block, "text");
                break;
            }
        }

        int inputTokens  = usage != null ? usage.path("input_tokens").asInt(0)  : 0;
        int outputTokens = usage != null ? usage.path("output_tokens").asInt(0) : 0;
        int totalTokens  = inputTokens + outputTokens;
        BigDecimal cost  = computeCost(model, inputTokens, outputTokens);

        Log.infof("Anthropic success: model=%s, intent=%s, attempt=%d, tokens=%d, cost=$%.6f, latency=%dms",
                model, intent.getId(), attempt, totalTokens, cost, latencyMs);

        return ExecutionRecord.of(
                intent.getId(),
                attempt,
                step.getAdapterId() != null ? step.getAdapterId().toString() : null,
                latencyMs,
                BigDecimal.valueOf(cost.doubleValue()),
                null,   // FailureType null = SUCCESS
                null    // PlanVersion
        );
    }

    // ── Cost ─────────────────────────────────────────────────────────────────

    private BigDecimal computeCost(String model, int inputTokens, int outputTokens) {
        BigDecimal inRate;
        BigDecimal outRate;

        if (model.contains("opus")) {
            inRate  = opusInputCostPer1k;
            outRate = opusOutputCostPer1k;
        } else if (model.contains("haiku")) {
            inRate  = haikuInputCostPer1k;
            outRate = haikuOutputCostPer1k;
        } else {
            inRate  = sonnetInputCostPer1k;
            outRate = sonnetOutputCostPer1k;
        }

        return inRate .multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .add(outRate.multiply(BigDecimal.valueOf(outputTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    // ── Exception mapping ────────────────────────────────────────────────────

    private RuntimeException mapException(Throwable ex, String model, int attempt, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        // thenApply() wraps exceptions from parseResponse() in CompletionException — unwrap it
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof LlmAdapterException lae) return lae;
        if (cause instanceof LlmTimeoutException  lte) return lte;

        if (cause.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return new LlmTimeoutException(
                    "Anthropic timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "Anthropic request failed: " + cause.getMessage(),
                PROVIDER, model, attempt, latencyMs);
    }

    // ── JsonNode helpers ──────────────────────────────────────────────────────

    private String safeText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? "" : child.asText();
    }
}