package com.decisionmesh.billing.api;

import com.decisionmesh.billing.service.StripeService;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/billing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BillingResource {

    @Inject StripeService stripeService;
    @Inject TenantContext tenantContext;

    /**
     * POST /api/billing/checkout
     *
     * UI sends:
     *   { "priceId": "price_xxx", "mode": "subscription"|"payment",
     *     "successUrl": "...", "cancelUrl": "..." }
     *
     * Returns:
     *   { "checkoutUrl": "https://checkout.stripe.com/..." }
     */
    @POST
    @Path("/checkout")
    public Response createCheckout(CheckoutRequest request) {
        if (request.priceId == null || request.priceId.isBlank())
            return Response.status(400).entity(Map.of("error", "priceId is required")).build();

        String orgId = tenantContext.getTenantId().toString();

        try {
            String url;

            if ("payment".equalsIgnoreCase(request.mode)) {
                // Credit pack — one-time payment
                // creditAmount defaults to 0 if not provided; webhook uses metadata
                int credits = request.creditAmount != null ? request.creditAmount : 0;
                url = stripeService.createCreditPackCheckout(
                        resolveCustomerId(orgId),
                        request.priceId,
                        orgId,
                        credits
                );
            } else {
                // Subscription (default)
                String plan = request.plan != null ? request.plan : "unknown";
                url = stripeService.createSubscriptionCheckout(
                        resolveCustomerId(orgId),
                        request.priceId,
                        orgId,
                        plan
                );
            }

            Log.infof("[Billing] Checkout created: orgId=%s mode=%s priceId=%s",
                    orgId, request.mode, request.priceId);

            return Response.ok(Map.of("checkoutUrl", url)).build();

        } catch (Exception e) {
            Log.errorf(e, "[Billing] Checkout failed: orgId=%s priceId=%s", orgId, request.priceId);
            return Response.status(500)
                    .entity(Map.of("error", "Checkout session creation failed", "detail", e.getMessage()))
                    .build();
        }
    }

    /**
     * Resolves the Stripe customer ID for the org.
     * TODO: replace stub with a lookup against billing_customer table.
     * For now returns orgId as placeholder so Stripe sandbox calls don't fail
     * on a null customerId — swap this once BillingCustomerRepository is wired.
     */
    private String resolveCustomerId(String orgId) {
        // stub — replace with: billingCustomerRepo.findByOrgId(orgId).stripeCustomerId
        return orgId;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class CheckoutRequest {
        public String  priceId;
        public String  mode;          // "subscription" | "payment"
        public String  plan;          // e.g. "builder", "pro" — subscription only
        public Integer creditAmount;  // credit pack only
        public String  successUrl;    // ignored — StripeService uses configured value
        public String  cancelUrl;     // ignored — StripeService uses configured value
    }
}