import { v4 as uuidv4 } from 'uuid';

const API_BASE = 'http://localhost:8080/api';

async function refreshToken(keycloak) {
  if (!keycloak.authenticated) { await keycloak.login(); return; }
  try { await keycloak.updateToken(30); } catch { await keycloak.login(); }
}

async function request(keycloak, path, options = {}) {
  await refreshToken(keycloak);
  if (!keycloak.authenticated) return;
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${keycloak.token}`,
      ...(options.headers ?? {}),
    },
  });
  if (res.status === 401) { await keycloak.login(); return; }
  if (res.status === 204) return null;
  if (!res.ok) throw new Error(await res.text());
  const text = await res.text();
  try { return JSON.parse(text); } catch { return text; }
}

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
 * Returns the AuthenticatedIdentity resolved by the server's IdentityAugmentor.
 * Useful for surfacing tenantId, userId, and roles in the UI without decoding
 * the JWT client-side.  Matches IntentResource.me().
 */
export async function getMe(keycloak) {
  return request(keycloak, '/intents/auth/me');
}

export async function getDashboardMetrics(keycloak) {
  return request(keycloak, '/dashboard/metrics');
}

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

export async function getCostAnalytics(keycloak, params = {}) {
  const qs = new URLSearchParams(params).toString();
  return request(keycloak, `/analytics/cost${qs ? `?${qs}` : ''}`);
}

export async function getDriftData(keycloak) {
  return request(keycloak, '/analytics/drift');
}

export async function listExecutions(keycloak, params = {}) {
  const qs = new URLSearchParams(
      Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return request(keycloak, `/executions${qs ? `?${qs}` : ''}`);
}

export async function listApiKeys(keycloak) {
  return request(keycloak, '/api-keys');
}

export async function createApiKey(keycloak) {
  return request(keycloak, '/api-keys', { method: 'POST' });
}

export async function revokeApiKey(keycloak, id) {
  return request(keycloak, `/api-keys/${id}`, { method: 'DELETE' });
}

export async function listAudit(keycloak, params = {}) {
  const qs = new URLSearchParams(
      Object.fromEntries(Object.entries(params).filter(([, v]) => v != null))
  ).toString();
  return request(keycloak, `/audit${qs ? `?${qs}` : ''}`);
}

export async function getAdapterPerformance(keycloak, id) {
  return request(keycloak, `/adapters/${id}/performance`);
}
