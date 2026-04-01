
package com.decisionmesh.billing.service;

import com.decisionmesh.billing.repository.BillingCustomerRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class BillingCustomerService {

    @Inject
    BillingCustomerRepository repository;

    public Uni<UUID> getOrgId(String stripeCustomerId) {
        return repository.findByStripeCustomerId(stripeCustomerId)
            .onItem().ifNull().failWith(() -> new RuntimeException("Customer not found"))
            .map(e -> e.orgId);
    }
}
