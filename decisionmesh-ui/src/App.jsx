import { useState, Suspense, lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import Sidebar from './layout/Sidebar';
import TopBar from './layout/TopBar';
import { Spinner } from './components/shared';
import LowCreditBanner from './components/shared/LowCreditBanner';

const Dashboard       = lazy(() => import('./pages/Dashboard'));
const Playground      = lazy(() => import('./pages/Playground'));
const IntentsTable    = lazy(() => import('./pages/IntentsTable'));
const IntentDetail    = lazy(() => import('./pages/IntentDetail'));
const ExecutionMonitor= lazy(() => import('./pages/ExecutionMonitor'));
const Adapters        = lazy(() => import('./pages/Adapters'));
const PolicyBuilder   = lazy(() => import('./pages/PolicyBuilder'));
const CostAnalytics   = lazy(() => import('./pages/CostAnalytics'));
const DriftDashboard  = lazy(() => import('./pages/DriftDashboard'));
const ApiKeys         = lazy(() => import('./pages/ApiKeys'));
const AuditLog        = lazy(() => import('./pages/AuditLog'));
const InviteUsers     = lazy(() => import('./pages/InviteUsers'));
const Projects        = lazy(() => import('./pages/Projects'));
const ProjectSettings = lazy(() => import('./pages/ProjectSettings'));
const UserProfile     = lazy(() => import('./pages/UserProfile'));
const OrgBranding     = lazy(() => import('./pages/OrgBranding'));
const Billing         = lazy(() => import('./pages/Billing'));
const CreditLedger    = lazy(() => import('./pages/CreditLedger'));

function PageFallback() {
  return (
    <div className="flex items-center justify-center h-64">
      <Spinner className="w-8 h-8" />
    </div>
  );
}

export default function App({ keycloak }) {
  const [collapsed, setCollapsed] = useState(false);
  const [hidden,    setHidden]    = useState(false);

  return (
    <div className="flex h-screen bg-slate-50 overflow-hidden">
      <div className={`transition-all duration-200 shrink-0 overflow-hidden ${hidden ? 'w-0' : ''}`}>
        <Sidebar collapsed={collapsed} onToggle={() => setCollapsed(c => !c)} onHide={() => setHidden(true)} />
      </div>
      <div className="flex flex-col flex-1 min-w-0">
        <TopBar keycloak={keycloak} sidebarHidden={hidden} onToggleSidebar={() => setHidden(h => !h)} />
        <LowCreditBanner />
        <main className="flex-1 overflow-y-auto p-4 scrollbar-thin">
          <Suspense fallback={<PageFallback />}>
            <Routes>
              <Route path="/"                  element={<Dashboard keycloak={keycloak} />} />
              <Route path="/playground"        element={<Playground keycloak={keycloak} />} />
              <Route path="/intents"           element={<IntentsTable keycloak={keycloak} />} />
              <Route path="/intents/:id"       element={<IntentDetail keycloak={keycloak} />} />
              <Route path="/executions"        element={<ExecutionMonitor keycloak={keycloak} />} />
              <Route path="/adapters"          element={<Adapters keycloak={keycloak} />} />
              <Route path="/policies"          element={<PolicyBuilder keycloak={keycloak} />} />
              <Route path="/analytics/cost"    element={<CostAnalytics keycloak={keycloak} />} />
              <Route path="/analytics/drift"   element={<DriftDashboard keycloak={keycloak} />} />
              <Route path="/api-keys"          element={<ApiKeys keycloak={keycloak} />} />
              <Route path="/audit"             element={<AuditLog keycloak={keycloak} />} />
              <Route path="/invite"            element={<InviteUsers keycloak={keycloak} />} />
              <Route path="/projects"          element={<Projects keycloak={keycloak} />} />
              <Route path="/projects/:id/settings" element={<ProjectSettings keycloak={keycloak} />} />
              <Route path="/profile"           element={<UserProfile keycloak={keycloak} />} />
              <Route path="/org/branding"      element={<OrgBranding keycloak={keycloak} />} />
              <Route path="/billing"           element={<Billing     keycloak={keycloak} />} />
              <Route path="/credits"           element={<CreditLedger keycloak={keycloak} />} />
              <Route path="*"                  element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </main>
      </div>
    </div>
  );
}
