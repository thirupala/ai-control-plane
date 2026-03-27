package com.decisionmesh.infrastructure.llm;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.ObjectiveType;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.infrastructure.llm.openai.OpenAILlmAdapter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class OpenAILlmAdapterTest {

    // Injected by CDI — @ConfigProperty fields are populated correctly
    @Inject
    OpenAILlmAdapter adapter;

    static WireMockServer wiremock;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wiremock.stop();
    }

    @AfterEach
    void resetStubs() {
        wiremock.resetAll();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void execute_success_returnsPopulatedRecord() {
        wiremock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                    {
                      "id": "chatcmpl-test",
                      "choices": [
                        { "message": { "role": "assistant", "content": "Hello AI response" } }
                      ],
                      "usage": {
                        "prompt_tokens": 20,
                        "completion_tokens": 10,
                        "total_tokens": 30
                      }
                    }
                """)));

        Intent   intent = buildIntent("Hello AI");
        PlanStep step   = buildStep("gpt-4o", wiremock.baseUrl() + "/v1/chat/completions");

        ExecutionRecord record = adapter.execute(intent, step, 1)
                .await().indefinitely();

        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getCost()).isGreaterThan(0.0);
        assertThat(record.getLatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(record.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void execute_rateLimited_throwsLlmAdapterException() {
        wiremock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(status(429).withBody("rate limit exceeded")));

        Intent   intent = buildIntent("Hello AI");
        PlanStep step   = buildStep("gpt-4o", wiremock.baseUrl() + "/v1/chat/completions");

        assertThatThrownBy(() -> adapter.execute(intent, step, 1).await().indefinitely())
                .isInstanceOf(LlmAdapterException.class)
                .satisfies(ex -> assertThat(((LlmAdapterException) ex).getFailureType())
                        .isEqualTo("RATE_LIMITED"));
    }

    @Test
    void execute_serverError_throwsAdapterError() {
        wiremock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(serverError().withBody("internal error")));

        Intent   intent = buildIntent("Hello AI");
        PlanStep step   = buildStep("gpt-4o", wiremock.baseUrl() + "/v1/chat/completions");

        assertThatThrownBy(() -> adapter.execute(intent, step, 1).await().indefinitely())
                .isInstanceOf(LlmAdapterException.class)
                .satisfies(ex -> assertThat(((LlmAdapterException) ex).getFailureType())
                        .isEqualTo("ADAPTER_ERROR"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Intent buildIntent(String prompt) {
        return Intent.fromRequest(
                "chat",
                IntentObjective.of(prompt, ObjectiveType.QUALITY),
                IntentConstraints.none(),
                Budget.of(1.0)
        );
    }

    private PlanStep buildStep(String model, String endpoint) {
        JsonObject config = new JsonObject()
                .put("provider",   "OPENAI")
                .put("model",      model)
                .put("endpoint",   endpoint)   // WireMock URL overrides DEFAULT_ENDPOINT
                .put("max_tokens", 100)
                .put("timeout_ms", 5000L);

        return PlanStep.builder()
                .stepId(UUID.randomUUID())
                .planId(UUID.randomUUID())
                .adapterId(UUID.randomUUID())
                .stepType("PRIMARY")
                .configSnapshot(config)
                .build();
    }
}
