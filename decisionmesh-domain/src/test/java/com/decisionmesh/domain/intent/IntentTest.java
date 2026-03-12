package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.intent.value.ObjectiveType;
import com.decisionmesh.domain.value.Budget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IntentTest {

    private Intent createIntent() {
        return Intent.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COST_OPTIMIZATION",
                IntentObjective.of(ObjectiveType.COST, 0.8, 0.1),
                IntentConstraints.of(
                        100.0,
                        Duration.ofSeconds(2),
                        0.8,
                        1,
                        Set.of("us-east-1"),
                        Set.of("gpt-4")
                ),
                Budget.of(100.0)
        );
    }

    @Test
    void shouldNotAllowInvalidTransition() {
        Intent intent = createIntent();
        assertThrows(IllegalStateException.class, intent::markExecuting);
    }

    @Test
    void shouldEnforceRetryFromExecutingOnly() {
        Intent intent = createIntent();
        assertThrows(IllegalStateException.class, intent::scheduleRetry);
    }

    @Test
    void shouldUpdateDriftWithinValidRange() {
        Intent intent = createIntent();

        intent.updateDrift(0.5);

        assertEquals(0.5, intent.getDriftScore().value());
    }

    @Test
    void shouldRejectNegativeDrift() {
        Intent intent = createIntent();

        assertThrows(IllegalArgumentException.class,
                () -> intent.updateDrift(-1.0));
    }
}
