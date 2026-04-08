import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom/client';
import { ReactKeycloakProvider, useKeycloak } from '@react-keycloak/web';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import keycloakInstance from './auth/keycloak';
import { ProjectProvider } from './context/ProjectContext';
import { BrandingProvider } from './context/BrandingContext';
import { CreditProvider } from './context/CreditContext';
import App from './App';
import LandingPage from './pages/LandingPage';
import { getMe } from './utils/api';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (count, err) => {
        const status = err?.response?.status;
        if (status >= 400 && status < 500) return false;
        return count < 2;
      },
    },
  },
});

const initOptions = {
  onLoad: 'check-sso',
  pkceMethod: 'S256',
  checkLoginIframe: false,
};

function FullScreenSpinner() {
  return (
      <div style={{
        height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: '#08080a',
      }}>
        <div style={{
          width: 32, height: 32,
          border: '2px solid rgba(37,99,235,0.3)',
          borderTopColor: '#2563eb',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
        }} />
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </div>
  );
}

function AppWrapper() {
  const { keycloak, initialized } = useKeycloak();
  const [provisioned, setProvisioned] = useState(false);

  // ── Silent token refresh every 60s ───────────────────────────────────────────
  useEffect(() => {
    if (!initialized || !keycloak.authenticated) return;
    const interval = setInterval(() => {
      keycloak.updateToken(70).catch(() => {});
    }, 60_000);
    return () => clearInterval(interval);
  }, [initialized, keycloak.authenticated]);

  // ── Provision user + force fresh token before rendering app ──────────────────
  // Called once per login. getMe() triggers provisionNewUser() on the backend
  // (idempotent — safe to call on every login). After provisioning, we force
  // a token refresh so tenantId + userId claims are present before BrandingContext,
  // ProjectContext and Dashboard fire their API calls.
  useEffect(() => {
    if (!initialized || !keycloak.authenticated || !keycloak.token) return;

    getMe(keycloak)
        .then(() => {
          // Force Keycloak to issue a completely fresh token by expiring the current one.
          // Without this, updateToken() serves the cached token which lacks tenantId.
          keycloak.tokenParsed.exp = 0;
          return keycloak.updateToken(-1);
        })
        .then(() => new Promise(resolve => setTimeout(resolve, 300))) // allow token propagation
        .then(() => setProvisioned(true))
        .catch(() => setProvisioned(true)); // fail open — never block the UI permanently

  }, [initialized, keycloak.authenticated, keycloak.token]);

  // Keycloak still initialising
  if (!initialized) return <FullScreenSpinner />;

  // Not authenticated — show landing page
  if (!keycloak.authenticated) return <LandingPage />;

  // Authenticated but provisioning + token refresh not done yet
  if (!provisioned) return <FullScreenSpinner />;

  // Provisioned and fresh token ready — safe to render app
  // All child contexts (Branding, Project, Credit) will now have
  // a token that contains tenantId + userId claims
  return (
      <BrandingProvider keycloak={keycloak}>
        <ProjectProvider keycloak={keycloak}>
          <CreditProvider keycloak={keycloak}>
            <App keycloak={keycloak} />
          </CreditProvider>
        </ProjectProvider>
      </BrandingProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
    <ReactKeycloakProvider authClient={keycloakInstance} initOptions={initOptions}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppWrapper />
        </BrowserRouter>
      </QueryClientProvider>
    </ReactKeycloakProvider>
);
