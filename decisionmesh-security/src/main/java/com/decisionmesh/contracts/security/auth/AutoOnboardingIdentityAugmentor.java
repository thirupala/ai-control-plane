package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.User;
import com.decisionmesh.contracts.security.service.UserService;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class AutoOnboardingIdentityAugmentor implements SecurityIdentityAugmentor {

    @Inject
    UserService userService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {

        return context.runBlocking(() -> {

            User user = userService.findByExternalUserId(
                    identity.getPrincipal().getName()
            );

            QuarkusSecurityIdentity.Builder builder =
                    QuarkusSecurityIdentity.builder(identity);

            if (user != null) {
                builder.addRole("USER");
            }

            return builder.build();
        });
    }
}