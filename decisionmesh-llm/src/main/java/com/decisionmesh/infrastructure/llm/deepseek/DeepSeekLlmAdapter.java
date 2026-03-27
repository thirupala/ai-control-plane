package com.decisionmesh.infrastructure.llm.deepseek;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.infrastructure.llm.prompt.PromptBuilder;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.infrastructure.llm.LlmAdapter;
import com.decisionmesh.infrastructure.llm.LlmAdapterException;
import com.decisionmesh.infrastructure.llm.LlmTimeoutException;
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
 * DeepSeek Chat adapter.
 *
 * Supported models:  deepseek-chat (DeepSeek-V3), deepseek-reasoner (DeepSeek-R1)
 * Wire format:       OpenAI-compatible — POST /v1/chat/completions
 * Auth:              Authorization: Bearer <api-key>
 *
 * DeepSeek's API is intentionally OpenAI-compatible so the request format
 * is identical. The response adds a reasoning_content field for deepseek-reasoner
 * which we capture in the output alongside the final answer.
 *
 * Cost model: prompt_tokens × input_price + completion_tokens × output_price
 *             deepseek-reasoner bills reasoning_tokens separately at a higher rate.
 */
@ApplicationScoped
public class DeepSeekLlmAdapter implements LlmAdapter {

    private static final String PROVIDER         = "DEEPSEEK";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "llm.deepseek.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.deepseek.default-model",      defaultValue = "deepseek-chat")
    String defaultModel;

    @ConfigProperty(name = "llm.deepseek.default-timeout-ms", defaultValue = "60000")
    int defaultTimeoutMs; // reasoner can be slow — 60s default

    // deepseek-chat (DeepSeek-V3) pricing
    @ConfigProperty(name = "llm.deepseek.deepseek-chat.input-cost-per-1k",  defaultValue = "0.00014")
    BigDecimal chatInputCostPer1k;

    @ConfigProperty(name = "llm.deepseek.deepseek-chat.output-cost-per-1k", defaultValue = "0.00028")
    BigDecimal chatOutputCostPer1k;

    // deepseek-reasoner (DeepSeek-R1) pricing — reasoning tokens billed at output rate
    @ConfigProperty(name = "llm.deepseek.deepseek-reasoner.input-cost-per-1k",  defaultValue = "0.00055")
    BigDecimal reasonerInputCostPer1k;

    @ConfigProperty(name = "llm.deepseek.deepseek-reasoner.output-cost-per-1k", defaultValue = "0.00219")
    BigDecimal reasonerOutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public DeepSeekLlmAdapter() {
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

        JsonObject config = step.getConfigSnapshot();
        String model      = config.getString("model",       defaultModel);
        int maxTokens     = config.getInteger("max_tokens", 2048);
        double temp       = config.getDouble("temperature", 0.0); // 0 recommended for reasoner
        String endpoint   = config.getString("endpoint",    DEFAULT_ENDPOINT);
        long timeoutMs    = config.getLong("timeout_ms",    (long) defaultTimeoutMs);

        JsonArray messages = PromptBuilder.buildMessages(intent);

        // OpenAI-compatible request format
        JsonObject requestBody = new JsonObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("temperature", temp)
                .put("messages", messages);

        Log.debugf("DeepSeek request: model=%s, intent=%s, attempt=%d",
                model, intent.getId(), attempt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type",  "application/json")
                .header("Authorization", "Bearer " + apiKey)
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

    private ExecutionRecord parseResponse(
            HttpResponse<String> response,
            Intent intent, PlanStep step, int attempt,
            String model, Instant startedAt) {

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response.statusCode() == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "DeepSeek rate limit exceeded: " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "DeepSeek HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonObject body    = new JsonObject(response.body());
        JsonObject usage   = body.getJsonObject("usage");
        JsonArray  choices = body.getJsonArray("choices");

        if (choices == null || choices.isEmpty()) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "DeepSeek returned empty choices",
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonObject message = choices.getJsonObject(0).getJsonObject("message");

        // deepseek-reasoner returns both reasoning_content and content
        String reasoningContent = message.getString("reasoning_content", null);
        String finalContent     = message.getString("content", "");

        // Combine reasoning trace + final answer if present
        String outputText = (reasoningContent != null && !reasoningContent.isBlank())
                ? "<thinking>\n" + reasoningContent + "\n</thinking>\n\n" + finalContent
                : finalContent;

        int promptTokens     = usage != null ? usage.getInteger("prompt_tokens",     0) : 0;
        int completionTokens = usage != null ? usage.getInteger("completion_tokens", 0) : 0;
        int totalTokens      = promptTokens + completionTokens;

        // Reasoning tokens are a subset of completion tokens for billing
        int reasoningTokens  = 0;
        if (usage != null) {
            JsonObject completionDetails = usage.getJsonObject("completion_tokens_details");
            if (completionDetails != null) {
                reasoningTokens = completionDetails.getInteger("reasoning_tokens", 0);
            }
        }

        BigDecimal cost = computeCost(model, promptTokens, completionTokens, reasoningTokens);

        Log.infof("DeepSeek success: model=%s, intent=%s, attempt=%d, tokens=%d (reasoning=%d), cost=$%.6f, latency=%dms",
                model, intent.getId(), attempt, totalTokens, reasoningTokens, cost, latencyMs);

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

    /**
     * DeepSeek-R1 (reasoner) bills reasoning tokens at the output rate.
     * Regular completion tokens (non-reasoning) are also billed at output rate.
     * Both are included in completion_tokens so we use a single output rate.
     */
    private BigDecimal computeCost(String model, int promptTokens,
                                    int completionTokens, int reasoningTokens) {
        boolean isReasoner = model.contains("reasoner");
        BigDecimal inRate  = isReasoner ? reasonerInputCostPer1k  : chatInputCostPer1k;
        BigDecimal outRate = isReasoner ? reasonerOutputCostPer1k : chatOutputCostPer1k;

        return inRate .multiply(BigDecimal.valueOf(promptTokens))    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
             .add(outRate.multiply(BigDecimal.valueOf(completionTokens)).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    // ── Exception mapping ────────────────────────────────────────────────────

    private RuntimeException mapException(Throwable ex, String model, int attempt, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        // thenApply() wraps exceptions from parseResponse() in CompletionException — unwrap it.
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof LlmAdapterException) { return (LlmAdapterException) cause; }
        if (cause instanceof LlmTimeoutException)  { return (LlmTimeoutException)  cause; }

        if (cause.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return new LlmTimeoutException(
                    "DeepSeek timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "DeepSeek request failed: " + cause.getMessage(),
                PROVIDER, model, attempt, latencyMs);
    }
}
