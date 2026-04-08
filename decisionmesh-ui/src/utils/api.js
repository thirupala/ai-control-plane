import { v4 as uuidv4 } from 'uuid';

// Single source of truth for the API base URL — reads from the Vite env var
// set in .env.development / .env.production so the app works outside localhost.
// Typed error so callers can distinguish auth failures (401/403) from
// other errors and show appropriate UI without catching everything blindly.
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name    = 'ApiError';
    this.status  = status;
    this.isAuth  = status === 401 || status === 403;
  }
}

export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api';

async function refreshToken(keycloak) {
  if (!keycloak?.authenticated) return false;
  try {
    await keycloak.updateToken(30);
    return true;
  } catch {
    // updateToken failed (session expired server-side, network issue, etc).
    // Return false so the caller can decide — do NOT call keycloak.login() here.
    // Calling login() when the backend rejects for non-expiry reasons
    // (email_verified: false, missing role/scope) causes an infinite redirect loop.
    return false;
  }
}

// Exported so contexts and pages can use it directly instead of
// duplicating their own fetch + auth logic.
export async function request(keycloak, path, options = {}) {
  await refreshToken(keycloak);

  // Guard on the token string itself, not just authenticated.
  // After a failed refresh keycloak.token can be undefined even when
  // authenticated is still true — sending "Bearer undefined" produces a 401.
  if (!keycloak?.authenticated || !keycloak?.token) return null;

  // Normalize caller-supplied headers to a plain object regardless of whether
  // they passed a Headers instance, a plain object, or nothing at all.
  // Then place Authorization LAST so it can never be overwritten by the caller.
  const callerHeaders = options.headers instanceof Headers
    ? Object.fromEntries(options.headers.entries())
    : (options.headers ?? {});

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...callerHeaders,
      Authorization: `Bearer ${keycloak.token}`, // always last — never overridden
    },
  });
  if (res.status === 401) {
    throw new ApiError(401, `Unauthorized — ${path}. Check Token Debugger (/debug/token) for details.`);
  }
  if (res.status === 204) return null;
  if (!res.ok) throw new ApiError(res.status, await res.text());
  const text = await res.text();
  try { return JSON.parse(text); } catch { return text; }
}

// ── Intents ───────────────────────────────────────────────────────────────────

export async function submitIntent(keycloak, intent) {
  return request(keycloak, '/intents', {
    method: 'POST',
    headers: { 'Idempotency-Key': uuidv4() },
    body: JSON.stringify(intent),
  });
}

export async function getIntent(keycloak, id) {
  return request(keycloak, `/intents/${id}`);
}

export async function listIntents(keycloak, params = {}) {
  const qs = new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return request(keycloak, `/intents${qs ? `?${qs}` : ''}`);
}

export async function getIntentEvents(keycloak, id) {
  return request(keycloak, `/intents/${id}/events`);
}

/**
 * GET /api/intents/auth/me
 *
 * Returns the  resolved by the server's IdentityAugmentor.
 * Useful for surfacing tenantId, userId, and roles in the UI without decoding
 * the JWT client-side.  Matches IntentResource.me().
 */
export async function getMe(keycloak) {
  return request(keycloak, '/onboard/me');
}

// ── Dashboard ─────────────────────────────────────────────────────────────────

export async function getDashboardMetrics(keycloak) {
  return request(keycloak, '/dashboard/metrics');
}

// ── Organisation & projects ───────────────────────────────────────────────────

export async function getOrg(keycloak) {
  return request(keycloak, '/org');
}

export async function listProjects(keycloak) {
  return request(keycloak, '/projects');
}

export async function getOrgBranding(keycloak) {
  return request(keycloak, '/org/branding');
}

// ── Credits ───────────────────────────────────────────────────────────────────

export async function getCreditBalance(keycloak) {
  return request(keycloak, '/credits/balance');
}

// ── Adapters ──────────────────────────────────────────────────────────────────

export async function listAdapters(keycloak) {
  return request(keycloak, '/adapters');
}

export async function createAdapter(keycloak, body) {
  return request(keycloak, '/adapters', { method: 'POST', body: JSON.stringify(body) });
}

export async function updateAdapter(keycloak, id, body) {
  return request(keycloak, `/adapters/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

export async function toggleAdapter(keycloak, id, isActive) {
  return request(keycloak, `/adapters/${id}/status`, {
    method: 'PATCH', body: JSON.stringify({ isActive }),
  });
}

export async function getAdapterPerformance(keycloak, id) {
  return request(keycloak, `/adapters/${id}/performance`);
}

// ── Policies ──────────────────────────────────────────────────────────────────

export async function listPolicies(keycloak) {
  return request(keycloak, '/policies');
}

export async function savePolicy(keycloak, body) {
  const method = body.policyId ? 'PUT' : 'POST';
  const path   = body.policyId ? `/policies/${body.policyId}` : '/policies';
  return request(keycloak, path, { method, body: JSON.stringify(body) });
}

export async function deletePolicy(keycloak, id) {
  return request(keycloak, `/policies/${id}`, { method: 'DELETE' });
}

// ── Analytics ─────────────────────────────────────────────────────────────────

export async function getCostAnalytics(keycloak, params = {}) {
  const qs = new URLSearchParams(params).toString();
  return request(keycloak, `/analytics/cost${qs ? `?${qs}` : ''}`);
}

export async function getDriftData(keycloak) {
  return request(keycloak, '/analytics/drift');
}

// ── Executions ────────────────────────────────────────────────────────────────

export async function listExecutions(keycloak, params = {}) {
  const qs = new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return request(keycloak, `/executions${qs ? `?${qs}` : ''}`);
}

// ── API keys ──────────────────────────────────────────────────────────────────

export async function listApiKeys(keycloak) {
  return request(keycloak, '/api-keys');
}

export async function createApiKey(keycloak) {
  return request(keycloak, '/api-keys', { method: 'POST' });
}

export async function revokeApiKey(keycloak, id) {
  return request(keycloak, `/api-keys/${id}`, { method: 'DELETE' });
}

// ── Audit ─────────────────────────────────────────────────────────────────────

export async function listAudit(keycloak, params = {}) {
  const qs = new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return request(keycloak, `/audit${qs ? `?${qs}` : ''}`);
}

// ── Billing ───────────────────────────────────────────────────────────────────

export async function getBillingSubscription(keycloak) {
  return request(keycloak, '/billing/subscription');
}

export async function getBillingUsage(keycloak) {
  return request(keycloak, '/billing/usage');
}

export async function createCheckout(keycloak, body) {
  return request(keycloak, '/billing/checkout', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
