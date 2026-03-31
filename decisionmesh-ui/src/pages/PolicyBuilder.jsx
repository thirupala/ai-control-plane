import { useState, useEffect } from 'react';
import { Plus, Trash2, ShieldCheck } from 'lucide-react';
import { v4 as uuidv4 } from 'uuid';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, EmptyState, Spinner } from '../components/shared';
import { listPolicies, savePolicy, deletePolicy } from '../utils/api';

const METRICS   = ['cost', 'latency', 'risk'];
const OPERATORS = ['>', '<', '='];
const ACTIONS   = ['REJECT', 'FALLBACK', 'RETRY'];

const NEW_RULE = () => ({ id: uuidv4(), metric: 'cost', operator: '>', value: 0.01, action: 'REJECT' });
const NEW_POLICY = () => ({ name: '', rules: [NEW_RULE()] });

function RuleRow({ rule, onChange, onDelete }) {
  const sel = (key, opts) => (
    <select value={rule[key]} onChange={e => onChange({ ...rule, [key]: e.target.value })}
      className="text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
      {opts.map(o => <option key={o}>{o}</option>)}
    </select>
  );

  return (
    <div className="flex items-center gap-2 flex-wrap">
      {sel('metric',   METRICS)}
      {sel('operator', OPERATORS)}
      <input type="number" step="0.001" value={rule.value}
        onChange={e => onChange({ ...rule, value: parseFloat(e.target.value) })}
        className="w-24 text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500"/>
      <span className="text-xs text-slate-400">→</span>
      {sel('action', ACTIONS)}
      <button onClick={onDelete} className="p-1 text-slate-300 hover:text-red-500 transition-colors ml-auto">
        <Trash2 size={13}/>
      </button>
    </div>
  );
}

function PolicyCard({ policy, onSave, onDelete }) {
  const [form, setForm]     = useState({ ...policy, rules: [...(policy.rules ?? [])] });
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty]   = useState(false);

  function updateRule(id, updated) {
    setForm(f => ({ ...f, rules: f.rules.map(r => r.id === id ? updated : r) }));
    setDirty(true);
  }

  function addRule() {
    setForm(f => ({ ...f, rules: [...f.rules, NEW_RULE()] }));
    setDirty(true);
  }

  function removeRule(id) {
    setForm(f => ({ ...f, rules: f.rules.filter(r => r.id !== id) }));
    setDirty(true);
  }

  async function handleSave() {
    setSaving(true);
    try { await onSave(form); setDirty(false); }
    finally { setSaving(false); }
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <input value={form.name} onChange={e => { setForm(f => ({...f, name: e.target.value})); setDirty(true); }}
          placeholder="Policy name…"
          className="text-sm font-semibold text-slate-800 bg-transparent focus:outline-none border-b border-transparent focus:border-blue-400 pb-0.5"/>
        <div className="flex gap-2">
          {dirty && <Button size="sm" loading={saving} onClick={handleSave}>Save</Button>}
          <Button variant="ghost" size="sm" onClick={() => onDelete(policy.policyId)}>
            <Trash2 size={13}/>
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-slate-400 font-medium uppercase tracking-wide">Rules (all apply)</p>
        {form.rules.map(r => (
          <RuleRow key={r.id} rule={r} onChange={u => updateRule(r.id, u)} onDelete={() => removeRule(r.id)}/>
        ))}
        <button onClick={addRule} className="text-xs text-blue-600 hover:text-blue-700 flex items-center gap-1 mt-1">
          <Plus size={12}/> Add rule
        </button>
      </CardContent>
    </Card>
  );
}

export default function PolicyBuilder({ keycloak }) {
  const [policies, setPolicies] = useState([]);
  const [loading, setLoading]   = useState(true);

  async function load() {
    try { const d = await listPolicies(keycloak); setPolicies(d ?? []); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [keycloak]);

  async function handleSave(policy) {
    await savePolicy(keycloak, policy);
    load();
  }

  async function handleDelete(id) {
    if (!id) { load(); return; }
    await deletePolicy(keycloak, id);
    load();
  }

  function handleAdd() {
    setPolicies(p => [{ policyId: null, ...NEW_POLICY() }, ...p]);
  }

  return (
    <Page title="Policy Builder" subtitle="Define rules that govern intent execution"
      action={<Button onClick={handleAdd}><Plus size={14}/> New policy</Button>}>
      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-8 h-8"/></div>
      ) : policies.length === 0 ? (
        <Card>
          <EmptyState icon={<ShieldCheck size={22}/>} title="No policies"
            description="Create a policy to enforce cost, latency, and risk rules"
            action={<Button onClick={handleAdd}><Plus size={14}/> New policy</Button>}/>
        </Card>
      ) : (
        <div className="space-y-4">
          {policies.map((p, i) => (
            <PolicyCard key={p.policyId ?? i} policy={p} onSave={handleSave} onDelete={handleDelete}/>
          ))}
        </div>
      )}
    </Page>
  );
}
