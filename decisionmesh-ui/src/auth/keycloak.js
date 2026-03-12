import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url:      'http://localhost:8180',
  realm:    'decisionmesh',
  clientId: 'control-plane-web'
});

export default keycloak;