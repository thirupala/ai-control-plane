package com.decisionmesh.billing.repository;

import com.decisionmesh.billing.model.CreditLedgerEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class CreditLedgerRepository
        implements PanacheRepositoryBase<CreditLedgerEntity, UUID> {

    /** Current balance = sum of all amounts for this org. */
    public Uni<Long> sumByOrgId(UUID orgId) {
        return find("orgId", orgId)
                .list()
                .map(entries -> entries.stream()
                        .mapToLong(e -> e.amount)
                        .sum());
    }
}
