import { useState } from 'react';
import { Send, RefreshCw, Copy, ExternalLink, Zap } from 'lucide-react';
import { useNavigate, useLocation } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../components/shared';
import ExecutionTimeline from '../components/timeline/ExecutionTimeline';
import { submitIntent } from '../utils/api';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';

const INTENT_TYPES = ['SUMMARIZATION', 'CHAT', 'CLASSIFICATION', 'CUSTOM'];

const DEFAULT = JSON.stringify({
  intentType:  'chat',
  objective:   { description: 'Hello AI' },
  constraints: { maxRetries: 3, timeoutSeconds: 30 },
  budget:      { ceilingUsd: 10.0, currency: 'USD' },
}, null, 2);

function ModelTierSelector({ selected, onChange }) {
  return (
      <Card>
        <CardHeader><CardTitle>AI model tier</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            {Object.entries(MODEL_TIERS).map(([key, tier]) => (
                <button key={key} type="button" onClick={() => onChange(key)}
                        className="text-left p-3 rounded-xl border-2 transition-all"
                        style={{ borderColor: selected === key ? tier.color : '#e2e8f0',
                          backgroundColor: selected === key ? tier.bg : 'white' }}>
                  <div className="flex items-center justify-between mb-1.5">
                    <span className="text-xs font-bold" style={{ color: tier.color }}>{tier.label}</span>
                    <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white"
                          style={{ backgroundColor: tier.color }}>
                  {tier.credits} cr
                </span>
                  </div>
                  <p className="text-[11px] text-slate-500 leading-tight">{tier.models}</p>
                  <p className="text-[10px] text-slate-400 mt-1">{tier.description}</p>
                </button>
            ))}
          </div>
          <p className="text-xs text-slate-400 mt-2.5">
            Credits are deducted per execution attempt. Retries each cost 1× the tier rate.
          </p>
        </CardContent>
      </Card>
  );
}

export default function Playground({ keycloak }) {
  const navigate = useNavigate();
  const location = useLocation();
  // Pre-fill from IntentsTable/IntentDetail "Resubmit" button
  const prefilledIntent = location.state?.intent ?? null;
  const { balance, isEmpty, deductCredits, refundCredits } = useCredits();

  const [json, setJson] = useState(() => {
    if (!prefilledIntent) return DEFAULT;
    // Rebuild a clean submit payload from the completed intent
    const { intentType, objective, constraints, budget } = prefilledIntent;
    return JSON.stringify({
      intentType:  intentType  ?? 'chat',
      objective:   objective   ?? { description: '' },
      constraints: constraints ?? { maxRetries: 3, timeoutSeconds: 30 },
      budget:      budget ? { ceilingUsd: budget.ceilingUsd, currency: budget.currency ?? 'USD' } : { ceilingUsd: 10.0, currency: 'USD' },
    }, null, 2);
  });
  const [jsonErr,    setJsonErr]   = useState(null);
  const [iKey,       setIKey]      = useState(uuidv4);
  const [tier,       setTier]      = useState('economy');
  const [loading,    setLoading]   = useState(false);
  const [result,     setResult]    = useState(null);
  const [error,      setError]     = useState(null);
  const [copied,     setCopied]    = useState(false);
  const [creditCost, setCreditCost] = useState(null);

  function handleChange(e) {
    setJson(e.target.value);
    try { JSON.parse(e.target.value); setJsonErr(null); }
    catch { setJsonErr('Invalid JSON'); }
  }

  async function handleSubmit() {
    if (isEmpty) { setError('No credits remaining. Top up to continue.'); return; }
    setError(null); setResult(null); setCreditCost(null);
    let body;
    try { body = JSON.parse(json); }
    catch { setError('Fix the JSON before submitting'); return; }
    body._modelTier = tier;
    setLoading(true);
    deductCredits(tier);
    try {
      const id = await submitIntent(keycloak, body);
      setResult(String(id));
      setCreditCost(MODEL_TIERS[tier].credits);
    } catch (e) {
      refundCredits(tier);
      setError(e.message);
    } finally { setLoading(false); }
  }

  function copy() {
    navigator.clipboard.writeText(result ?? '');
    setCopied(true); setTimeout(() => setCopied(false), 2000);
  }

  const tierData  = MODEL_TIERS[tier];
  const canSubmit = !jsonErr && !loading && !isEmpty && balance !== null;

  return (
      <Page title="Playground"
            subtitle={prefilledIntent
                ? <span className="text-xs font-medium text-blue-600 bg-blue-50 px-2 py-0.5 rounded-full">
            ↩ Resubmitting intent {prefilledIntent.id?.split('-')[0]}
          </span>
                : 'Submit intents and watch execution in real time'}
            action={result && (
                <Button variant="secondary" size="sm"
                        onClick={() => { setResult(null); setCreditCost(null); setIKey(uuidv4()); }}>
                  <RefreshCw size={13} /> New intent
                </Button>
            )}>
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          <div className="space-y-4">
            <ModelTierSelector selected={tier} onChange={setTier} />

            <Card>
              <CardHeader><CardTitle>Intent type</CardTitle></CardHeader>
              <CardContent>
                <div className="flex gap-2 flex-wrap">
                  {INTENT_TYPES.map(t => {
                    const sel = (() => { try { return JSON.parse(json)?.intentType?.toUpperCase() === t; } catch { return false; } })();
                    return (
                        <button key={t} type="button"
                                onClick={() => { try { const p = JSON.parse(json); p.intentType = t.toLowerCase(); setJson(JSON.stringify(p, null, 2)); setJsonErr(null); } catch {/***/} }}
                                className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${sel ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'}`}>
                          {t}
                        </button>
                    );
                  })}
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader><div className="flex items-center justify-between"><CardTitle>Payload</CardTitle><span className="text-xs text-slate-400">JSON</span></div></CardHeader>
              <CardContent className="p-0">
              <textarea value={json} onChange={handleChange} rows={13}
                        className="w-full font-mono text-xs p-4 resize-none focus:outline-none rounded-b-xl text-slate-700 bg-slate-50"
                        style={{ fontFamily: "'JetBrains Mono', monospace" }} />
                {jsonErr && <p className="px-4 pb-3 text-xs text-red-500">{jsonErr}</p>}
              </CardContent>
            </Card>

            <Card>
              <CardHeader><CardTitle>Request metadata</CardTitle></CardHeader>
              <CardContent>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-xs font-medium text-slate-600">Idempotency key</label>
                  <button onClick={() => setIKey(uuidv4())} className="text-xs text-blue-600 flex items-center gap-1">
                    <RefreshCw size={11} /> Regenerate
                  </button>
                </div>
                <input readOnly value={iKey}
                       className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 bg-slate-50 text-slate-500"
                       style={{ fontFamily: "'JetBrains Mono', monospace" }} />
              </CardContent>
            </Card>

            {/* Credit cost + submit */}
            <div className="space-y-2">
              <div className="flex items-center justify-between px-1">
                <div className="flex items-center gap-2 text-sm">
                  <Zap size={13} style={{ color: tierData.color }} />
                  <span className="text-slate-600">
                  Cost: <strong style={{ color: tierData.color }}>{tierData.credits} credit{tierData.credits !== 1 ? 's' : ''}</strong>
                  <span className="text-xs text-slate-400 ml-1">({tierData.label} tier)</span>
                </span>
                </div>
                {balance !== null && (
                    <span className="text-xs text-slate-400">
                  Balance: <strong style={{ color: balance <= 0 ? '#dc2626' : balance < 50 ? '#d97706' : '#16a34a' }}>
                    {balance?.toLocaleString()}
                  </strong>
                </span>
                )}
              </div>
              <Button className="w-full" size="lg" loading={loading} disabled={!canSubmit} onClick={handleSubmit}>
                <Send size={14} />
                {isEmpty ? 'No credits — top up to submit' : 'Submit intent'}
              </Button>
              {isEmpty && (
                  <button onClick={() => navigate('/billing')} className="w-full text-xs text-blue-600 underline text-center">
                    Buy credits or upgrade plan →
                  </button>
              )}
            </div>

            {error && <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-xs text-red-700">{error}</div>}
          </div>

          <div>
            {result ? (
                <Card>
                  <CardHeader className="flex flex-row items-center justify-between">
                    <div className="flex items-center gap-2">
                      <CardTitle>Intent submitted</CardTitle>
                      {creditCost && (
                          <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                                style={{ backgroundColor: tierData.bg, color: tierData.color }}>
                      −{creditCost} credit{creditCost !== 1 ? 's' : ''}
                    </span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <Button variant="secondary" size="sm" onClick={copy}><Copy size={12} />{copied ? 'Copied!' : 'Copy ID'}</Button>
                      <Button variant="secondary" size="sm" onClick={() => navigate(`/intents/${result}`)}><ExternalLink size={12} /> Detail</Button>
                    </div>
                  </CardHeader>
                  <CardContent>
                    <div className="mb-4 p-3 rounded-lg bg-green-50 border border-green-200">
                      <p className="text-xs text-green-700 font-medium mb-1">Intent ID</p>
                      <p className="font-mono text-sm text-green-800 break-all" style={{ fontFamily: "'JetBrains Mono', monospace" }}>{result}</p>
                    </div>
                    <p className="text-sm font-medium text-slate-700 mb-4">Execution timeline</p>
                    <ExecutionTimeline keycloak={keycloak} intentId={result} currentPhase="CREATED" terminal={false} satisfied={false} />
                  </CardContent>
                </Card>
            ) : (
                <Card className="h-full flex items-center justify-center min-h-96 border-dashed border-slate-200 bg-transparent shadow-none">
                  <div className="text-center text-slate-400 p-8">
                    <Send size={28} className="mx-auto mb-3 opacity-20" />
                    <p className="text-sm font-medium">Submit an intent</p>
                    <p className="text-xs mt-1 text-slate-300">The execution timeline appears here</p>
                    <p className="text-xs mt-3 font-semibold" style={{ color: tierData.color }}>
                      {tierData.credits} credit{tierData.credits !== 1 ? 's' : ''} per execution · {tierData.label} tier
                    </p>
                  </div>
                </Card>
            )}
          </div>
        </div>
      </Page>
  );
}
