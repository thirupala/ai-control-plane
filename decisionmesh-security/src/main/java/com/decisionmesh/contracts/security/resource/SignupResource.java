package com.decisionmesh.contracts.security.resource;

import com.decisionmesh.contracts.security.resource.dto.SignupRequest;
import com.decisionmesh.contracts.security.resource.dto.SignupResponse;
import com.decisionmesh.contracts.security.service.SignupService;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/onboarding")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class SignupResource {

    @Inject
    SignupService signupService;

    @POST
    public Uni<SignupResponse> onboard(
            @Valid SignupRequest request,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Missing Idempotency-Key header");
        }

        //  Pass idempotencyKey from header into request
        request.idempotencyKey = idempotencyKey;

        return  signupService.onboard(request);
    }
}