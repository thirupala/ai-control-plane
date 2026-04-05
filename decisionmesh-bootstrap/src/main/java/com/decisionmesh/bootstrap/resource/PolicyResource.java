package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.PolicyDto;
import com.decisionmesh.bootstrap.dto.PolicyDto.SavePolicyRequest;
import com.decisionmesh.bootstrap.service.AuditService;
import com.decisionmesh.bootstrap.service.PolicyService;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for policy management.
 *
 * Endpoints consumed by {@code PolicyBuilder.jsx} via {@code utils/api.js}:
 * <pre>
 *   GET    /api/policies          → List&lt;PolicyDto&gt;   (list all tenant policies)
 *   POST   /api/policies          → PolicyDto           (create — policyId null in body)
 *   PUT    /api/policies/{id}     → PolicyDto           (update — policyId set in body)
 *   DELETE /api/policies/{id}     → 204                 (delete)
 * </pre>
 *
 * <h3>Key: policyId vs id</h3>
 * The UI uses {@code policy.policyId} everywhere (not {@code policy.id}).
 * {@link PolicyDto} maps {@code PolicyEntity.id → PolicyDto.policyId}.
 * Path param {@code {id}} matches {@code policyId} in the URL that {@code savePolicy}
 * builds: {@code /policies/${body.policyId}}.
 *
 * <h3>savePolicy routing in api.js</h3>
 * <pre>
 * const method = body.policyId ? 'PUT' : 'POST';
 * const path   = body.policyId ? `/policies/${body.policyId}` : '/policies';
 * </pre>
 * So POST is for new policies (policyId null) and PUT is for updates.
 */
@Path("/api/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class PolicyResource {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    PolicyService policyService;

    @Inject
    AuditService auditService;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/policies
    //
    // PolicyBuilder.jsx useEffect calls listPolicies(keycloak).
    // Returns all policies for the tenant (active + inactive so the user can
    // see and manage all of them).
    // Each item has: { policyId, name, rules, phase, enforcementMode, priority }
    // ─────────────────────────────────────────────────────────────────────────

    @GET
    @WithSession
    @NonBlocking
    public Uni<List<PolicyDto>> list() {
        return policyService.list(resolveIdentity().tenantId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/policies
    //
    // Called by savePolicy() when body.policyId is null (new unsaved policy).
    // PolicyBuilder.handleAdd() inserts { policyId: null, name: '', rules: [] }
    // into local state. When the user clicks Save in PolicyCard, handleSave()
    // calls savePolicy() which routes here because policyId is null.
    //
    // Returns the created PolicyDto so the UI gets the server-assigned policyId
    // and can update its local state — allowing subsequent edits to go to PUT.
    // ─────────────────────────────────────────────────────────────────────────

    @POST
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<Response> create(SavePolicyRequest body) {
        if (body == null || body.name == null || body.name.isBlank()) {
            throw new BadRequestException("Policy name is required");
        }
        AuthenticatedIdentity authC = resolveIdentity();
        return policyService.create(authC.tenantId(), body)
                .map(dto -> {
                    auditService.log(authC.tenantId(), String.valueOf(authC.userId()),
                            AuditService.ACTION_POLICY_CREATED,
                            "POLICY", dto.policyId, "Created: " + dto.name);
                    return Response.status(Response.Status.CREATED).entity(dto).build();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /api/policies/{id}
    //
    // Called by savePolicy() when body.policyId is set (existing policy).
    // PolicyCard.handleSave() calls onSave(form) → handleSave(policy) →
    // savePolicy(keycloak, policy) → PUT /api/policies/${policy.policyId}.
    //
    // {id} in the path is policy.policyId which maps to PolicyEntity.id.
    // ─────────────────────────────────────────────────────────────────────────

    @PUT
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<PolicyDto> update(@PathParam("id") UUID id, SavePolicyRequest body) {
        if (body == null || body.name == null || body.name.isBlank()) {
            throw new BadRequestException("Policy name is required");
        }
        AuthenticatedIdentity authU = resolveIdentity();
        return policyService.update(authU.tenantId(), id, body)
                .invoke(dto -> auditService.log(authU.tenantId(), String.valueOf(authU.userId()),
                        AuditService.ACTION_POLICY_UPDATED,
                        "POLICY", id, "Updated: " + dto.name));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /api/policies/{id}
    //
    // Called by deletePolicy(keycloak, id) where id = policy.policyId.
    // PolicyCard delete button calls onDelete(policy.policyId).
    // handleDelete(id) in PolicyBuilder: if (!id) { load(); return; }
    // so unsaved policies (policyId null) are removed from local state only —
    // no server call — which is correct.
    // ─────────────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<Response> delete(@PathParam("id") UUID id) {
        AuthenticatedIdentity authD = resolveIdentity();
        return policyService.delete(authD.tenantId(), id)
                .invoke(() -> auditService.log(authD.tenantId(), String.valueOf(authD.userId()),
                        AuditService.ACTION_POLICY_DELETED,
                        "POLICY", id, null))
                .replaceWith(Response.noContent().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identity helper
    // ─────────────────────────────────────────────────────────────────────────

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth =
                securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException(
                    "Identity not resolved — check IdentityAugmentor");
        }
        return auth;
    }
}