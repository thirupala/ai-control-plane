import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw, DollarSign, Target, Hash, Clock, Trash2, RotateCcw, AlertTriangle } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, PhaseBadge, SatisfactionBadge, Spinner } from '../components/shared';
import ExecutionTimeline from '../components/timeline/ExecutionTimeline';
import { getIntent, deleteIntent } from '../utils/api';
import { formatCost, formatDate, shortId } from '../lib/utils';

function Row({ label, value }) {
  return (
      <div className="flex items-start py-2 border-b border-slate-50 last:border-0">
        <span className="text-xs text-slate-400 w-36 shrink-0 pt-0.5">{label}</span>
        <span className="text-xs text-slate-700 font-medium flex-1">{value}</span>
      </div>
  );
}

export default function IntentDetail({ keycloak }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [intent, setIntent]   = useState(null);
  const [loading, setLoading] = useState(true);
  const [fetching, setFetching] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  async function load() {
    setFetching(true);
    try {
      const data = await getIntent(keycloak, id);
      setIntent(data);
    } finally { setLoading(false); setFetching(false); }
  }

  async function handleDelete() {
    try {
      await deleteIntent(keycloak, id);
      navigate('/intents');
    } catch (e) {
      alert('Delete failed: ' + (e?.message ?? 'unknown error'));
    }
  }

  useEffect(() => {
    load();
    const t = setInterval(() => { if (intent && !intent.terminal) load(); }, 5000);
    return () => clearInterval(t);
  }, [id]);

  if (loading) return (
      <Page title="Intent detail">
        <div className="flex justify-center py-24"><Spinner className="w-8 h-8"/></div>
      </Page>
  );

  if (!intent) return (
      <Page title="Intent detail">
        <Card className="p-12 text-center text-sm text-slate-500">Intent not found</Card>
      </Page>
  );

  const spentPct = intent.budget
      ? Math.min(100, (intent.budget.spentUsd / intent.budget.ceilingUsd) * 100)
      : 0;

  return (
      <Page
          title={`Intent ${shortId(intent.id)}`}
          subtitle={`Created ${formatDate(intent.createdAt)}`}
          action={
            <div className="flex gap-2">
              <Button variant="ghost" size="sm" onClick={() => navigate('/intents')}>
                <ArrowLeft size={13}/> Back
              </Button>
              <Button variant="secondary" size="sm" loading={fetching} onClick={load}>
                <RefreshCw size={13}/> Refresh
              </Button>
              {intent?.terminal && (
                  <>
                    <Button variant="secondary" size="sm"
                            onClick={() => navigate('/playground', { state: { intent } })}>
                      <RotateCcw size={13}/> Resubmit
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => setConfirmDelete(true)}>
                      <Trash2 size={13}/> Delete
                    </Button>
                  </>
              )}
            </div>
          }
      >
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Left: info cards */}
          <div className="space-y-4">
            <Card className="p-5">
              <div className="flex items-center gap-2 mb-4">
                <PhaseBadge phase={intent.phase}/>
                <SatisfactionBadge state={intent.satisfactionState}/>
              </div>
              <Row label="Intent ID" value={
                <span className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded break-all"
                      style={{ fontFamily: "'JetBrains Mono', monospace" }}>{intent.id}</span>
              }/>
              <Row label="Type"      value={intent.intentType}/>
              <Row label="Version"   value={`v${intent.version}`}/>
              <Row label="Retries"   value={`${intent.retryCount ?? 0} / ${intent.maxRetries ?? 0}`}/>
              <Row label="Terminal"  value={intent.terminal ? 'Yes' : 'No'}/>
              <Row label="Drift"     value={(intent.driftScore ?? 0).toFixed(4)}/>
            </Card>

            {intent.budget && (
                <Card>
                  <CardHeader>
                    <div className="flex items-center gap-2"><DollarSign size={13} className="text-slate-400"/><CardTitle>Budget</CardTitle></div>
                  </CardHeader>
                  <CardContent>
                    <Row label="Ceiling"  value={formatCost(intent.budget.ceilingUsd)}/>
                    <Row label="Spent"    value={formatCost(intent.budget.spentUsd)}/>
                    <Row label="Currency" value={intent.budget.currency}/>
                    <Row label="Exceeded" value={intent.budget.exceeded
                        ? <span className="text-red-600">Yes</span> : 'No'}/>
                    <div className="mt-3">
                      <div className="flex justify-between text-[10px] text-slate-400 mb-1">
                        <span>Spent</span><span>{spentPct.toFixed(1)}%</span>
                      </div>
                      <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
                        <div className="h-full bg-blue-500 rounded-full" style={{ width: `${spentPct}%` }}/>
                      </div>
                    </div>
                  </CardContent>
                </Card>
            )}

            {intent.constraints && (
                <Card>
                  <CardHeader>
                    <div className="flex items-center gap-2"><Target size={13} className="text-slate-400"/><CardTitle>Constraints</CardTitle></div>
                  </CardHeader>
                  <CardContent>
                    <Row label="Max latency"  value={`${intent.constraints.maxLatency ?? 0}ms`}/>
                    <Row label="Max retries"  value={intent.constraints.maxRetries ?? 0}/>
                    <Row label="Timeout"      value={`${intent.constraints.timeoutSeconds ?? 0}s`}/>
                    <Row label="Risk limit"   value={(intent.constraints.maxDriftThreshold ?? 0).toFixed(2)}/>
                  </CardContent>
                </Card>
            )}

            {intent.objective && (
                <Card>
                  <CardHeader>
                    <div className="flex items-center gap-2"><Hash size={13} className="text-slate-400"/><CardTitle>Objective</CardTitle></div>
                  </CardHeader>
                  <CardContent>
                <pre className="text-xs text-slate-600 bg-slate-50 rounded-lg p-3 overflow-x-auto whitespace-pre-wrap break-all"
                     style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                  {JSON.stringify(intent.objective, null, 2)}
                </pre>
                  </CardContent>
                </Card>
            )}
          </div>

          {/* Right: timeline */}
          <div className="lg:col-span-2">
            <Card>
              <CardHeader>
                <div className="flex items-center gap-2">
                  <Clock size={13} className="text-slate-400"/>
                  <CardTitle>Execution timeline</CardTitle>
                  {!intent.terminal && (
                      <span className="ml-auto flex items-center gap-1.5 text-xs text-blue-600">
                    <span className="w-1.5 h-1.5 rounded-full bg-blue-600 animate-pulse"/>Live
                  </span>
                  )}
                </div>
              </CardHeader>
              <CardContent>
                <ExecutionTimeline
                    keycloak={keycloak}
                    intentId={intent.id}
                    currentPhase={intent.phase}
                    terminal={intent.terminal}
                    satisfied={intent.satisfactionState === 'SATISFIED'}
                />
              </CardContent>
            </Card>
          </div>
        </div>
        {/* Delete confirmation modal */}
        {confirmDelete && (
            <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
              <div className="bg-white rounded-xl border border-slate-200 shadow-lg p-6 max-w-sm w-full space-y-4">
                <div className="flex items-start gap-3">
                  <div className="p-2 rounded-lg bg-red-50 shrink-0"><AlertTriangle size={16} className="text-red-600"/></div>
                  <div>
                    <p className="text-sm font-semibold text-slate-800">Delete this intent?</p>
                    <p className="text-xs text-slate-500 mt-1">
                      This permanently removes the intent and all its events. This cannot be undone.
                    </p>
                  </div>
                </div>
                <div className="flex justify-end gap-2">
                  <Button variant="secondary" size="sm" onClick={() => setConfirmDelete(false)}>Cancel</Button>
                  <Button variant="destructive" size="sm" onClick={handleDelete}>
                    <Trash2 size={12}/> Delete
                  </Button>
                </div>
              </div>
            </div>
        )}
      </Page>
  );
}