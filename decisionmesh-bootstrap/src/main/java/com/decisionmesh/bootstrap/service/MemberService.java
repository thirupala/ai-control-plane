package com.decisionmesh.bootstrap.service;

import com.decisionmesh.contracts.security.entity.MemberEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class MemberService {

    public Uni<MemberEntity> addMember(UUID tenantId, UUID projectId, UUID userId, String role) {
        MemberEntity member = new MemberEntity();
        member.tenantId = tenantId;
        member.projectId = projectId;
        member.userId = userId;
        member.role = role;
        return Panache.withTransaction(member::persist);
    }

    public Uni<Void> trackActivity(UUID userId, UUID projectId) {
        return Panache.withTransaction(() ->
                MemberEntity.find("userId = ?1 and projectId = ?2", userId, projectId)
                        .firstResult()
                        .map(result -> (MemberEntity) result) // Explicit cast for Panache
                        .onItem().ifNotNull().invoke(member -> {
                            member.lastActiveAt = Instant.now(); // This will now resolve
                        })
                        .replaceWithVoid()
        );
    }
}