import React, { useEffect } from 'react';
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
import './index.css';

window._kc = keycloakInstance;

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

// check-sso: Keycloak checks silently if already logged in.
// If not authenticated it does NOT redirect — landing page shows instead.
// login-required would redirect immediately, skipping the landing page.
const initOptions = {
  onLoad: 'check-sso',
  pkceMethod: 'S256',
  checkLoginIframe: false,
};

function AppWrapper() {
  const { keycloak, initialized } = useKeycloak();

  useEffect(() => {
    if (!initialized || !keycloak.authenticated) return;
    const interval = setInterval(() => {
      keycloak.updateToken(70).catch(() => keycloak.login());
    }, 60_000);
    return () => clearInterval(interval);
  }, [initialized, keycloak]);

  // Still initialising — show minimal spinner
  if (!initialized) {
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

  // Not authenticated → show marketing landing page
  if (!keycloak.authenticated) {
    return <LandingPage />;
  }

  // Authenticated → show the control plane dashboard
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
