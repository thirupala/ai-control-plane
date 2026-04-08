import { useState, useEffect } from 'react';
import { CreditCard, Check, Zap, Shield, BarChart3, Users, ArrowRight, Star,
         AlertCircle, ExternalLink, ShoppingCart, RefreshCw } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';
import { formatDate } from '../lib/utils';
import { getBillingSubscription, getBillingUsage, createCheckout } from '../utils/api';

// ── Plans ─────────────────────────────────────────────────────────────────────
const PLANS = [
  { id: 'free',       name: 'Free',       price: 0,    interval: null,    color: '#64748b',
    credits: 500, note: 'One-time gift', cta: 'Current free tier',
    stripePriceId: null,
    features: ['500 credits (one-time)', '2 adapters', 'Budget enforcement', 'Basic audit (30 days)', 'Community support'] },
  { id: 'hobby',      name: 'Hobby',      price: 0,    interval: 'month', color: '#475569',
    credits: 2000, note: '2k credits/mo', cta: 'Start Hobby',
    stripePriceId: 'price_hobby_monthly',
    features: ['2,000 credits/month', '3 adapters', 'Full audit (90 days)', 'Email support'], },
  { id: 'builder',    name: 'Builder',    price: 19,   interval: 'month', color: '#2563eb', popular: true,
    credits: 15000, note: '15k credits/mo', cta: 'Upgrade to Builder',
    stripePriceId: 'price_builder_monthly',
    features: ['15,000 credits/month', 'All adapters', 'Policy builder', 'Decision replay',
               'Full audit + CSV export', 'Drift detection', 'Priority support', 'Overage: $0.002/credit'] },
  { id: 'pro',        name: 'Pro',        price: 49,   interval: 'month', color: '#4f46e5',
    credits: 60000, note: '60k credits/mo', cta: 'Upgrade to Pro',
    stripePriceId: 'price_pro_monthly',
    features: ['60,000 credits/month', 'Multi-tenancy', '5 team seats', 'SSO / SAML',
               'Human-in-the-loop gates', 'Priority support', 'Overage: $0.001/credit'] },
  { id: 'enterprise', name: 'Enterprise', price: null, interval: null,    color: '#7c3aed',
    credits: null, note: 'Unlimited', cta: 'Contact sales',
    stripePriceId: null,
    features: ['Unlimited credits', 'PII detection & masking', 'Model version tracking',
               'Immutable signed audit log', 'GDPR data residency', 'HIPAA / PCI-DSS templates',
               'BYOK (bring your own API key)', 'Dedicated SLA'] },
];

// ── Credit packs ──────────────────────────────────────────────────────────────
const CREDIT_PACKS = [
  { id: 'starter', name: 'Starter',     price: 10,  credits: 12000,  perCredit: '$0.00083', color: '#64748b',
    stripePriceId: 'price_credits_starter', note: '' },
  { id: 'growth',  name: 'Growth',      price: 25,  credits: 32000,  perCredit: '$0.00078', color: '#2563eb',
    stripePriceId: 'price_credits_growth', note: 'Best value', popular: true },
  { id: 'scale',   name: 'Scale',       price: 75,  credits: 100000, perCredit: '$0.00075', color: '#7c3aed',
    stripePriceId: 'price_credits_scale', note: '' },
];

// ── Plan card ─────────────────────────────────────────────────────────────────
function PlanCard({ plan, currentPlanId, onSelect, loading }) {
  const isCurrentPlan = plan.id === currentPlanId;
  return (
    <div className={`relative flex flex-col rounded-2xl border-2 p-5 transition-all ${
      plan.popular ? 'border-blue-500 shadow-lg shadow-blue-100' : isCurrentPlan ? 'border-slate-300' : 'border-slate-200 hover:border-slate-300'
    }`}>
      {plan.popular && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2">
          <span className="flex items-center gap-1 bg-blue-600 text-white text-xs font-semibold px-3 py-1 rounded-full">
            <Star size={10} fill="white" /> Most popular
          </span>
        </div>
      )}
      {isCurrentPlan && (
        <div className="absolute top-3 right-3">
          <span className="text-xs font-medium text-slate-500 bg-slate-100 px-2 py-0.5 rounded-full">Current</span>
        </div>
      )}

      <div className="mb-4">
        <div className="flex items-center gap-2 mb-1">
          <span className="w-2 h-2 rounded-full" style={{ backgroundColor: plan.color }} />
          <h3 className="text-sm font-bold text-slate-900">{plan.name}</h3>
        </div>
        <div className="flex items-end gap-1 mb-1">
          {plan.price === null ? (
            <span className="text-2xl font-bold text-slate-900">Custom</span>
          ) : plan.price === 0 ? (
            <span className="text-2xl font-bold text-slate-900">Free</span>
          ) : (
            <>
              <span className="text-2xl font-bold text-slate-900">${plan.price}</span>
              <span className="text-slate-400 text-xs mb-0.5">/{plan.interval}</span>
            </>
          )}
        </div>
        <p className="text-xs font-medium" style={{ color: plan.color }}>{plan.note}</p>
      </div>

      <ul className="flex-1 space-y-1.5 mb-4">
        {plan.features.map(f => (
          <li key={f} className="flex items-start gap-1.5 text-xs text-slate-600">
            <Check size={12} className="shrink-0 mt-0.5" style={{ color: plan.color }} />
            {f}
          </li>
        ))}
      </ul>

      {plan.id === 'enterprise' ? (
        <a href="mailto:sales@decisionmesh.io"
          className="flex items-center justify-center gap-2 py-2 px-4 rounded-xl text-xs font-semibold border-2 transition-colors"
          style={{ borderColor: plan.color, color: plan.color }}>
          Contact sales <ArrowRight size={12} />
        </a>
      ) : isCurrentPlan ? (
        <div className="flex items-center justify-center gap-1.5 py-2 px-4 rounded-xl text-xs font-semibold bg-slate-100 text-slate-500">
          <Check size={12} /> Current plan
        </div>
      ) : (
        <Button loading={loading === plan.id} onClick={() => onSelect(plan)}
          className="w-full justify-center text-xs"
          style={plan.popular ? { backgroundColor: plan.color } : {}}>
          {plan.cta} <ArrowRight size={11} />
        </Button>
      )}
    </div>
  );
}

// ── Credit pack card ──────────────────────────────────────────────────────────
function CreditPackCard({ pack, onBuy, loading }) {
  return (
    <div className={`relative rounded-2xl border-2 p-5 transition-all hover:shadow-md ${
      pack.popular ? 'border-blue-400' : 'border-slate-200'
    }`}>
      {pack.popular && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2">
          <span className="bg-blue-600 text-white text-xs font-semibold px-3 py-1 rounded-full">{pack.note}</span>
        </div>
      )}
      <div className="text-center mb-3">
        <p className="text-sm font-bold text-slate-900">{pack.name}</p>
        <p className="text-2xl font-bold mt-1" style={{ color: pack.color }}>${pack.price}</p>
        <p className="text-xs font-semibold text-slate-700 mt-0.5">
          {pack.credits.toLocaleString()} credits
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">{pack.perCredit} per credit</p>
      </div>
      <div className="mb-4 p-2 rounded-lg bg-slate-50 text-xs text-slate-500 text-center">
        ~{Math.floor(pack.credits / MODEL_TIERS.economy.credits).toLocaleString()} Economy intents<br />
        ~{Math.floor(pack.credits / MODEL_TIERS.standard.credits).toLocaleString()} Standard intents
      </div>
      <Button loading={loading === pack.id} onClick={() => onBuy(pack)}
        className="w-full justify-center text-xs"
        style={{ backgroundColor: pack.color }}>
        <ShoppingCart size={11} /> Buy for ${pack.price}
      </Button>
    </div>
  );
}

// ── Usage bar ─────────────────────────────────────────────────────────────────
function UsageBar({ label, used, limit, color = '#2563eb' }) {
  const pct = limit ? Math.min(100, (used / limit) * 100) : 0;
  const usedColor = pct > 85 ? '#dc2626' : pct > 60 ? '#d97706' : color;
  return (
    <div>
      <div className="flex justify-between text-xs mb-1">
        <span className="text-slate-600 font-medium">{label}</span>
        <span className="text-slate-500">{used?.toLocaleString() ?? 0}{limit ? ` / ${limit.toLocaleString()}` : ''}</span>
      </div>
      <div className="h-2 bg-slate-100 rounded-full overflow-hidden">
        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: usedColor }} />
      </div>
    </div>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────
export default function Billing({ keycloak }) {
  const [searchParams]                = useSearchParams();
  const navigate                      = useNavigate();
  const { balance, allocated, reload }= useCredits();

  const [subscription, setSubscription] = useState(null);
  const [usage,        setUsage]        = useState(null);
  const [loading,      setLoading]      = useState(true);
  const [selecting,    setSelecting]    = useState(null);
  const [tab,          setTab]          = useState(searchParams.get('tab') === 'credits' ? 'credits' : 'plans');

  useEffect(() => {
    // Fix: replaced private api() helper (which had its own hardcoded URL and
    // duplicated auth logic) with named functions from utils/api.js that use
    // the shared request() helper — consistent token refresh and base URL.
    Promise.allSettled([
      getBillingSubscription(keycloak),
      getBillingUsage(keycloak),
    ]).then(([sub, use]) => {
      if (sub.value) setSubscription(sub.value);
      if (use.value) setUsage(use.value);
    }).finally(() => setLoading(false));
  }, []);

  async function handleSelectPlan(plan) {
    if (!plan.stripePriceId) return;
    setSelecting(plan.id);
    try {
      const res = await createCheckout(keycloak, {
        priceId:    plan.stripePriceId,
        mode:       'subscription',
        successUrl: `${window.location.origin}/billing?success=1`,
        cancelUrl:  `${window.location.origin}/billing?cancelled=1`,
      });
      if (res?.checkoutUrl) window.location.href = res.checkoutUrl;
      else window.open('https://buy.stripe.com/test_placeholder', '_blank');
    } catch { window.open('https://buy.stripe.com/test_placeholder', '_blank'); }
    finally { setSelecting(null); }
  }

  async function handleBuyCredits(pack) {
    setSelecting(pack.id);
    try {
      const res = await createCheckout(keycloak, {
        priceId:    pack.stripePriceId,
        mode:       'payment',
        successUrl: `${window.location.origin}/billing?success=1&credits=${pack.credits}`,
        cancelUrl:  `${window.location.origin}/billing?cancelled=1`,
      });
      if (res?.checkoutUrl) window.location.href = res.checkoutUrl;
      else window.open('https://buy.stripe.com/test_placeholder', '_blank');
    } catch { window.open('https://buy.stripe.com/test_placeholder', '_blank'); }
    finally { setSelecting(null); }
  }

  const currentPlan   = subscription?.plan ?? 'free';
  const isSuccess     = searchParams.get('success') === '1';
  const isCancelled   = searchParams.get('cancelled') === '1';
  const creditsBought = searchParams.get('credits');

  return (
    <Page title="Billing & Plans" subtitle="Manage your subscription, credits, and usage">
      {isSuccess && (
        <div className="flex items-start gap-3 p-4 bg-emerald-50 border border-emerald-200 rounded-xl">
          <Check size={16} className="text-emerald-600 shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-semibold text-emerald-800">
              {creditsBought ? `${parseInt(creditsBought).toLocaleString()} credits added!` : 'Subscription activated!'}
            </p>
            <p className="text-xs text-emerald-700 mt-0.5">
              {creditsBought ? 'Credits are now available in your account.' : 'All features are now available.'}
            </p>
          </div>
        </div>
      )}
      {isCancelled && (
        <div className="flex items-center gap-3 p-4 bg-slate-50 border border-slate-200 rounded-xl">
          <AlertCircle size={15} className="text-slate-400" />
          <p className="text-sm text-slate-600">Checkout cancelled — no changes made.</p>
        </div>
      )}

      {loading ? <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div> : (
        <>
          {/* Credit balance summary */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card className="md:col-span-1 p-5">
              <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">Credit balance</p>
              <div className="flex items-end gap-2 mb-3">
                <span className="text-4xl font-bold" style={{ color: balance <= 0 ? '#dc2626' : balance < 100 ? '#d97706' : '#16a34a' }}>
                  {balance?.toLocaleString() ?? '—'}
                </span>
                <span className="text-slate-400 text-sm mb-1">/ {allocated?.toLocaleString()} credits</span>
              </div>
              <div className="h-2 bg-slate-100 rounded-full overflow-hidden mb-3">
                <div className="h-full rounded-full transition-all"
                  style={{ width: `${allocated ? Math.min(100, (balance / allocated) * 100) : 0}%`,
                           backgroundColor: balance <= 0 ? '#dc2626' : balance < 100 ? '#d97706' : '#16a34a' }} />
              </div>
              <p className="text-xs text-slate-400">
                Plan: <strong className="text-slate-600 capitalize">{currentPlan}</strong>
                {subscription?.currentPeriodEnd && (
                  <> · Renews {formatDate(subscription.currentPeriodEnd)}</>
                )}
              </p>
            </Card>

            <Card className="md:col-span-2 p-5">
              <div className="flex items-center justify-between mb-4">
                <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Usage this period</p>
                {subscription?.stripeCustomerPortalUrl && (
                  <a href={subscription.stripeCustomerPortalUrl} target="_blank" rel="noreferrer"
                    className="text-xs text-blue-600 flex items-center gap-1">
                    Manage subscription <ExternalLink size={11} />
                  </a>
                )}
              </div>
              <div className="space-y-3">
                <UsageBar label="Credits used" used={(allocated ?? 500) - (balance ?? 0)} limit={allocated ?? 500} />
                {usage && <>
                  <UsageBar label="Intents executed" used={usage.intentsUsed} limit={usage.intentsLimit} color="#4f46e5" />
                  <UsageBar label="Team members" used={usage.membersUsed} limit={usage.membersLimit} color="#7c3aed" />
                </>}
              </div>
            </Card>
          </div>

          {/* Tab bar */}
          <div className="flex gap-1 border-b border-slate-200">
            {[{ id: 'plans', label: 'Plans' }, { id: 'credits', label: 'Buy credits' }].map(({ id, label }) => (
              <button key={id} onClick={() => setTab(id)}
                className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                  tab === id ? 'border-blue-600 text-blue-700' : 'border-transparent text-slate-500 hover:text-slate-700'
                }`}>
                {label}
              </button>
            ))}
          </div>

          {tab === 'plans' && (
            <div>
              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-4">
                {PLANS.map(plan => (
                  <PlanCard key={plan.id} plan={plan} currentPlanId={currentPlan}
                    onSelect={handleSelectPlan} loading={selecting} />
                ))}
              </div>

              {/* Model tier cost guide */}
              <Card className="mt-6">
                <CardHeader><CardTitle>Credit cost by model tier</CardTitle></CardHeader>
                <CardContent>
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {Object.entries(MODEL_TIERS).map(([key, tier]) => (
                      <div key={key} className="p-4 rounded-xl border-2 transition-all"
                        style={{ borderColor: tier.color, backgroundColor: tier.bg }}>
                        <div className="flex items-center justify-between mb-2">
                          <span className="text-sm font-bold" style={{ color: tier.color }}>{tier.label}</span>
                          <span className="text-lg font-bold" style={{ color: tier.color }}>{tier.credits} cr</span>
                        </div>
                        <p className="text-xs text-slate-600 mb-1">{tier.models}</p>
                        <p className="text-xs text-slate-500">{tier.description}</p>
                      </div>
                    ))}
                  </div>
                  <p className="text-xs text-slate-400 mt-3 text-center">
                    Credits deducted per execution attempt · Retries each cost 1× the tier rate · 1 credit ≈ $0.008
                  </p>
                </CardContent>
              </Card>

              <p className="text-xs text-slate-400 text-center mt-4">
                Payments processed securely by{' '}
                <a href="https://stripe.com" target="_blank" rel="noreferrer" className="text-blue-500 underline">Stripe</a>.
                Cancel anytime. No hidden fees.
              </p>
            </div>
          )}

          {tab === 'credits' && (
            <div>
              <p className="text-sm text-slate-600 mb-5">
                Top up your credit balance without changing your plan. Credits never expire and carry over month to month.
              </p>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                {CREDIT_PACKS.map(pack => (
                  <CreditPackCard key={pack.id} pack={pack}
                    onBuy={handleBuyCredits} loading={selecting} />
                ))}
              </div>

              <Card className="mt-6 bg-blue-50 border-blue-200 shadow-none">
                <CardContent className="py-4 flex items-start gap-3">
                  <Zap size={16} className="text-blue-600 shrink-0 mt-0.5" />
                  <div className="text-xs text-blue-800">
                    <p className="font-semibold mb-1">Credits vs subscription — what's the difference?</p>
                    <p>Your subscription gives you a monthly credit allocation that resets each billing period.
                      Credit packs are one-time top-ups that stack on top of your allocation and never expire.
                      Use packs when you have a big project or spike in usage.</p>
                  </div>
                </CardContent>
              </Card>

              <p className="text-xs text-slate-400 text-center mt-4">
                Payments processed by <a href="https://stripe.com" target="_blank" rel="noreferrer" className="text-blue-500 underline">Stripe</a>.
                Credits added instantly after payment.
              </p>
            </div>
          )}
        </>
      )}
    </Page>
  );
}
