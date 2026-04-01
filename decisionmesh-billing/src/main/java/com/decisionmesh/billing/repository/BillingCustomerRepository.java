
package com.decisionmesh.billing.repository;

import com.decisionmesh.billing.model.BillingCustomerEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class BillingCustomerRepository implements PanacheRepositoryBase<BillingCustomerEntity, UUID> {

    public Uni<BillingCustomerEntity> findByStripeCustomerId(String stripeCustomerId) {
        return find("stripeCustomerId", stripeCustomerId).firstResult();
    }
}
