package com.decisionmesh.health;


import com.decisionmesh.application.lock.LockManager;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.util.UUID;

@Liveness
@ApplicationScoped
public class LockManagerHealthCheck implements HealthCheck {

    @Inject
    LockManager lockManager;

    @Override
    public HealthCheckResponse call() {
        String testKey = "healthcheck:" + UUID.randomUUID();

        try {
            // Try to acquire and immediately release
            lockManager.acquire(testKey, Duration.ofSeconds(5))
                    .flatMap(token -> lockManager.release(token))
                    .await().atMost(Duration.ofSeconds(2));

            return HealthCheckResponse.up("lock-manager");

        } catch (Exception e) {
            Log.errorf(e, "Lock manager health check failed");
            return HealthCheckResponse.down("lock-manager");
        }
    }
}