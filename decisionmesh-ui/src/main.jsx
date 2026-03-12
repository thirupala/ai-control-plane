import React, { useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import { ReactKeycloakProvider, useKeycloak } from '@react-keycloak/web';
import keycloakInstance from './auth/keycloak'; //  renamed to avoid shadowing
import App from './App';

//  expose for debugging
window._kc = keycloakInstance;

const initOptions = {
    onLoad: 'login-required',
    pkceMethod: 'S256',
    checkLoginIframe: false,
};

function AppWrapper() {
    const { keycloak, initialized } = useKeycloak(); // keycloak === keycloakInstance

    useEffect(() => {
        if (!initialized || !keycloak.authenticated) return;

        // Proactively refresh token every 60s, redirect to login if session expired
        const interval = setInterval(() => {
            keycloak.updateToken(70)
                .catch(() => {
                    keycloak.login();
                });
        }, 60000);

        return () => clearInterval(interval);
    }, [initialized, keycloak]);

    if (!initialized) return <div>Loading...</div>;
    return <App keycloak={keycloak} />;
}

ReactDOM.createRoot(document.getElementById('root')).render(
    <ReactKeycloakProvider
        authClient={keycloakInstance}
        initOptions={initOptions}
    >
        <AppWrapper />
    </ReactKeycloakProvider>
);
