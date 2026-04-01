package com.decisionmesh.billing.service;

import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class StripeService {

    @ConfigProperty(name = "stripe.secret.key")
    String stripeSecretKey;

    @ConfigProperty(name = "stripe.success.url", defaultValue = "http://localhost:5173/billing?success=1")
    String successUrl;

    @ConfigProperty(name = "stripe.cancel.url", defaultValue = "http://localhost:5173/billing?cancelled=1")
    String cancelUrl;

    @PostConstruct
    void init() {
        Stripe.apiKey = stripeSecretKey;
        Log.info("[Stripe] API key initialised");
    }

    // ── Subscription checkout (recurring) ─────────────────────────────────────

    /**
     * Creates a Stripe Checkout Session for a subscription plan (HOBBY / BUILDER / PRO).
     * Returns the hosted checkout URL — frontend redirects to this.
     *
     * Metadata carries orgId and plan so the webhook can read them
     * without another Stripe API call.
     */
    public String createSubscriptionCheckout(String customerId,
                                             String priceId,
                                             String orgId,
                                             String plan) throws Exception {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .putMetadata("orgId", orgId)
                .putMetadata("plan",  plan)
                .putMetadata("mode",  "subscription")
                .build();

        Session session = Session.create(params);
        Log.infof("[Stripe] Subscription checkout created: orgId=%s plan=%s", orgId, plan);
        return session.getUrl();
    }

    // ── Credit pack checkout (one-time payment) ───────────────────────────────

    /**
     * Creates a Stripe Checkout Session for a one-time credit pack purchase.
     * creditAmount is stored in metadata so the webhook knows how many credits
     * to grant without hardcoding pack sizes in the webhook handler.
     *
     * Credit packs:
     *   Starter  $10  → 1,500 credits  (price_credits_starter)
     *   Growth   $25  → 4,000 credits  (price_credits_growth)
     *   Scale    $75  → 12,500 credits (price_credits_scale)
     */
    public String createCreditPackCheckout(String customerId,
                                           String priceId,
                                           String orgId,
                                           int creditAmount) throws Exception {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}&credits=" + creditAmount)
                .setCancelUrl(cancelUrl)
                .putMetadata("orgId",         orgId)
                .putMetadata("mode",          "credit_pack")
                .putMetadata("creditAmount",  String.valueOf(creditAmount))
                .build();

        Session session = Session.create(params);
        Log.infof("[Stripe] Credit pack checkout created: orgId=%s credits=%d", orgId, creditAmount);
        return session.getUrl();
    }
}
