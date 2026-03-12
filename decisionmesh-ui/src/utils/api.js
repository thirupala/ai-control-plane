import { v4 as uuidv4 } from "uuid";

const API_BASE = "http://localhost:8080/api";

/**
 * Ensures the Keycloak token is fresh before making a request.
 * Redirects to login if the session has fully expired.
 */

async function refreshToken(keycloak) {
  if (!keycloak.authenticated) {
    await keycloak.login();
    return; // ← don't throw, let login redirect
  }
  try {
    const refreshed = await keycloak.updateToken(30);
    console.log('token refreshed:', refreshed, 'token:', keycloak.token?.substring(0, 30));
  } catch {
    await keycloak.login();
    return; // ← don't throw
  }
}

async function handleResponse(res, keycloak) {
  if (res.status === 401) {
    console.log('Got 401 — token that was sent:', keycloak.token?.substring(0, 50));
    await keycloak.login();
    return; // ← don't throw, let redirect happen
  }
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function submitIntent(keycloak, intent) {
  await refreshToken(keycloak);
  if (!keycloak.authenticated) return; // guard after potential login redirect

  console.log('Sending token:', keycloak.token?.substring(0, 50));

  const res = await fetch(`${API_BASE}/intents`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${keycloak.token}`,
      "Idempotency-Key": uuidv4()
    },
    body: JSON.stringify(intent)
  });

  return handleResponse(res, keycloak);
}

export async function getIntent(keycloak, id) {
  await refreshToken(keycloak);

  const res = await fetch(`${API_BASE}/intents/${id}`, {
    headers: {
      Authorization: `Bearer ${keycloak.token}`
    }
  });

  return handleResponse(res, keycloak);
}
