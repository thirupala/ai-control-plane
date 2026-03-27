package com.decisionmesh.infrastructure.llm.gemini;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.infrastructure.llm.LlmAdapter;
import com.decisionmesh.infrastructure.llm.LlmAdapterException;
import com.decisionmesh.infrastructure.llm.LlmTimeoutException;
import com.decisionmesh.infrastructure.llm.prompt.PromptBuilder;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Google Gemini GenerateContent adapter.
 *
 * Supported models:  gemini-2.0-flash, gemini-1.5-pro, gemini-1.5-flash
 * Wire format:       POST /v1beta/models/{model}:generateContent?key={apiKey}
 * Auth:              API key as query parameter
 *
 * FIX 3.1: requestBody uses PromptBuilder.buildGeminiContents(intent) directly.
 * Previous version had a stale `prompt` variable that was never defined after
 * the PromptBuilder migration — this fix ensures geminiContents is used correctly.
 */
@ApplicationScoped
public class GeminiLlmAdapter implements LlmAdapter {

    private static final String PROVIDER         = "GEMINI";
    private static final String BASE_URL         = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String GENERATE_CONTENT = ":generateContent";

    @ConfigProperty(name = "llm.gemini.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.gemini.default-model",      defaultValue = "gemini-2.0-flash")
    String defaultModel;

    @ConfigProperty(name = "llm.gemini.default-timeout-ms", defaultValue = "30000")
    int defaultTimeoutMs;

    @ConfigProperty(name = "llm.gemini.gemini-2-0-flash.input-cost-per-1k",  defaultValue = "0.00010")
    BigDecimal flash2InputCostPer1k;

    @ConfigProperty(name = "llm.gemini.gemini-2-0-flash.output-cost-per-1k", defaultValue = "0.00040")
    BigDecimal flash2OutputCostPer1k;

    @ConfigProperty(name = "llm.gemini.gemini-1-5-pro.input-cost-per-1k",    defaultValue = "0.00125")
    BigDecimal pro15InputCostPer1k;

    @ConfigProperty(name = "llm.gemini.gemini-1-5-pro.output-cost-per-1k",   defaultValue = "0.00500")
    BigDecimal pro15OutputCostPer1k;

    @ConfigProperty(name = "llm.gemini.gemini-1-5-flash.input-cost-per-1k",  defaultValue = "0.000075")
    BigDecimal flash15InputCostPer1k;

    @ConfigProperty(name = "llm.gemini.gemini-1-5-flash.output-cost-per-1k", defaultValue = "0.000300")
    BigDecimal flash15OutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public GeminiLlmAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Uni<ExecutionRecord> execute(Intent intent, PlanStep step, int attempt) {
        Instant startedAt = Instant.now();

        JsonObject config = step.getConfigSnapshot();
        String model      = config.getString("model",       defaultModel);
        int maxTokens     = config.getInteger("max_tokens", 1024);
        double temp       = config.getDouble("temperature", 0.2);
        long timeoutMs    = config.getLong("timeout_ms",    (long) defaultTimeoutMs);

        // FIX 3.1: Build Gemini-format contents via PromptBuilder — NOT a raw prompt string.
        // PromptBuilder.buildGeminiContents() returns [{parts:[{text:"system\n\nuser content"}]}]
        // which is exactly what Gemini's generateContent endpoint expects.
        JsonArray geminiContents = PromptBuilder.buildGeminiContents(intent);

        JsonObject requestBody = new JsonObject()
                .put("contents", geminiContents)          // ← uses geminiContents, not undefined `prompt`
                .put("generationConfig", new JsonObject()
                        .put("maxOutputTokens", maxTokens)
                        .put("temperature", temp));

        String endpoint = String.format("%s/%s%s?key=%s", BASE_URL, model, GENERATE_CONTENT, apiKey);

        Log.debugf("Gemini request: model=%s, intent=%s, attempt=%d", model, intent.getId(), attempt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.encode()))
                .build();

        CompletableFuture<ExecutionRecord> future = (CompletableFuture<ExecutionRecord>) httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(response, intent, step, attempt, model, startedAt))
                .exceptionally(ex -> {
                    RuntimeException mapped = mapException(ex, model, attempt, startedAt);
                    throw mapped;
                });

        return Uni.createFrom().completionStage(future);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExecutionRecord parseResponse(HttpResponse<String> response,
                                           Intent intent, PlanStep step, int attempt,
                                           String model, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response.statusCode() == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "Gemini rate limit exceeded: " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "Gemini HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonObject body       = new JsonObject(response.body());
        JsonArray  candidates = body.getJsonArray("candidates");

        if (candidates == null || candidates.isEmpty()) {
            JsonObject feedback = body.getJsonObject("promptFeedback");
            String blockReason  = feedback != null ? feedback.getString("blockReason", "UNKNOWN") : "UNKNOWN";
            throw new LlmAdapterException("POLICY_BLOCK",
                    "Gemini blocked prompt: blockReason=" + blockReason,
                    PROVIDER, model, attempt, latencyMs);
        }

        String outputText = "";
        try {
            outputText = candidates.getJsonObject(0)
                    .getJsonObject("content")
                    .getJsonArray("parts")
                    .getJsonObject(0)
                    .getString("text", "");
        } catch (Exception e) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "Gemini response missing text in candidates[0].content.parts[0]",
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonObject usage         = body.getJsonObject("usageMetadata");
        int promptTokens         = usage != null ? usage.getInteger("promptTokenCount",     0) : 0;
        int completionTokens     = usage != null ? usage.getInteger("candidatesTokenCount", 0) : 0;
        int totalTokens          = promptTokens + completionTokens;
        BigDecimal cost          = computeCost(model, promptTokens, completionTokens);

        Log.infof("Gemini success: model=%s, intent=%s, attempt=%d, tokens=%d, cost=$%.6f, latency=%dms",
                model, intent.getId(), attempt, totalTokens, cost, latencyMs);

        return ExecutionRecord.of(
                intent.getId(),
                attempt,
                step.getAdapterId() != null ? step.getAdapterId().toString() : null,
                latencyMs,
                cost != null ? cost.doubleValue() : 0.0,
                null,  // FailureType.null = SUCCESS
                null   // PlanVersion
        );
    }

    // ── Cost ─────────────────────────────────────────────────────────────────

    private BigDecimal computeCost(String model, int inputTokens, int outputTokens) {
        BigDecimal inRate;
        BigDecimal outRate;
        if (model.contains("2.0-flash") || model.contains("2-0-flash")) {
            inRate = flash2InputCostPer1k;   outRate = flash2OutputCostPer1k;
        } else if (model.contains("1.5-pro") || model.contains("1-5-pro")) {
            inRate = pro15InputCostPer1k;    outRate = pro15OutputCostPer1k;
        } else {
            inRate = flash15InputCostPer1k;  outRate = flash15OutputCostPer1k;
        }
        return inRate .multiply(BigDecimal.valueOf(inputTokens)) .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
             .add(outRate.multiply(BigDecimal.valueOf(outputTokens)).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    // ── Exception mapping ────────────────────────────────────────────────────

    private RuntimeException mapException(Throwable ex, String model, int attempt, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        // thenApply() wraps exceptions from parseResponse() in CompletionException — unwrap it.
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof LlmAdapterException) { return (LlmAdapterException) cause; }
        if (cause instanceof LlmTimeoutException)  { return (LlmTimeoutException)  cause; }
        if (cause.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return new LlmTimeoutException("Gemini timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "Gemini request failed: " + cause.getMessage(), PROVIDER, model, attempt, latencyMs);
    }
}
