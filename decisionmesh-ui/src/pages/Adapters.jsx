import { useState, useEffect } from 'react';
import { Plus, Puzzle, Edit2 } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, Button, EmptyState, Spinner, cn } from '../components/shared';
import { listAdapters, toggleAdapter, createAdapter, updateAdapter } from '../utils/api';
import { formatDate } from '../lib/utils';

const PROVIDERS = ['OPENAI','ANTHROPIC','GOOGLE','AZURE','CUSTOM'];
const TYPES     = ['LLM','TOOL','EMBEDDING','RERANKER'];

const DEFAULT_FORM = { name:'', provider:'OPENAI', modelId:'', adapterType:'LLM', config:'{}', isActive:true };

function AdapterModal({ adapter, onSave, onClose }) {
  const [form, setForm] = useState(adapter
    ? { ...adapter, config: JSON.stringify(adapter.config ?? {}, null, 2) }
    : DEFAULT_FORM
  );
  const [saving, setSaving]   = useState(false);
  const [jsonErr, setJsonErr] = useState(null);

  async function handleSave() {
    let cfg;
    try { cfg = JSON.parse(form.config); } catch { setJsonErr('Invalid JSON config'); return; }
    setSaving(true);
    try { await onSave({ ...form, config: cfg }); onClose(); }
    finally { setSaving(false); }
  }

  const field = (label, key, type = 'text', opts) => (
    <div>
      <label className="block text-xs font-medium text-slate-600 mb-1.5">{label}</label>
      {opts ? (
        <select value={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
          className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500">
          {opts.map(o => <option key={o}>{o}</option>)}
        </select>
      ) : (
        <input type={type} value={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
          className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"/>
      )}
    </div>
  );

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <Card className="w-full max-w-lg">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-800">{adapter ? 'Edit adapter' : 'Add adapter'}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-lg leading-none">×</button>
        </div>
        <div className="p-5 space-y-4">
          {field('Name',         'name')}
          {field('Provider',     'provider',     'text', PROVIDERS)}
          {field('Model ID',     'modelId')}
          {field('Adapter type', 'adapterType',  'text', TYPES)}
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Config (JSON)</label>
            <textarea value={form.config}
              onChange={e => { setForm(f => ({...f, config: e.target.value})); try { JSON.parse(e.target.value); setJsonErr(null); } catch { setJsonErr('Invalid JSON'); }}}
              rows={5} className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
              style={{ fontFamily: "'JetBrains Mono', monospace" }}/>
            {jsonErr && <p className="text-xs text-red-500 mt-1">{jsonErr}</p>}
          </div>
          <label className="flex items-center gap-2 cursor-pointer">
            <input type="checkbox" checked={form.isActive} onChange={e => setForm(f => ({...f, isActive: e.target.checked}))} className="rounded"/>
            <span className="text-sm text-slate-700">Active</span>
          </label>
        </div>
        <div className="px-5 py-4 border-t border-slate-100 flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button loading={saving} disabled={!!jsonErr} onClick={handleSave}>Save</Button>
        </div>
      </Card>
    </div>
  );
}

export default function Adapters({ keycloak }) {
  const [adapters, setAdapters] = useState([]);
  const [loading, setLoading]   = useState(true);
  const [modal, setModal]       = useState(null); // null | 'new' | adapter obj

  async function load() {
    try { const d = await listAdapters(keycloak); setAdapters(d ?? []); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [keycloak]);

  async function handleToggle(adapter) {
    await toggleAdapter(keycloak, adapter.id, !adapter.isActive);
    load();
  }

  async function handleSave(form) {
    if (form.id) await updateAdapter(keycloak, form.id, form);
    else await createAdapter(keycloak, form);
    load();
  }

  return (
    <Page title="Adapters" subtitle="Manage AI model adapters"
      action={<Button onClick={() => setModal('new')}><Plus size={14}/> Add adapter</Button>}>
      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-8 h-8"/></div>
      ) : adapters.length === 0 ? (
        <Card>
          <EmptyState icon={<Puzzle size={22}/>} title="No adapters configured"
            description="Add an adapter to start routing intents to AI models"
            action={<Button onClick={() => setModal('new')}><Plus size={14}/> Add adapter</Button>}/>
        </Card>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {adapters.map(a => (
            <Card key={a.id} className="p-5">
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="text-sm font-semibold text-slate-800">{a.name}</p>
                  <p className="text-xs text-slate-500 mt-0.5">{a.provider} · {a.adapterType}</p>
                </div>
                <button
                  onClick={() => handleToggle(a)}
                  className={`w-9 h-5 rounded-full transition-colors relative shrink-0 ${a.isActive ? 'bg-blue-600' : 'bg-slate-200'}`}>
                  <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${a.isActive ? 'translate-x-4' : 'translate-x-0.5'}`}/>
                </button>
              </div>
              <div className="space-y-1 text-xs text-slate-500">
                <div className="flex gap-2">
                  <span className="text-slate-400 w-16 shrink-0">Model</span>
                  <span className="font-mono truncate" style={{ fontFamily: "'JetBrains Mono', monospace" }}>{a.modelId}</span>
                </div>
                <div className="flex gap-2">
                  <span className="text-slate-400 w-16 shrink-0">ID</span>
                  <span className="font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>{a.id?.split('-')[0]}</span>
                </div>
                <div className="flex gap-2">
                  <span className="text-slate-400 w-16 shrink-0">Created</span>
                  <span>{formatDate(a.createdAt)}</span>
                </div>
              </div>
              <div className="mt-4 flex justify-end">
                <Button variant="ghost" size="sm" onClick={() => setModal(a)}>
                  <Edit2 size={13}/> Edit config
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      {modal && (
        <AdapterModal
          adapter={modal === 'new' ? null : modal}
          onSave={handleSave}
          onClose={() => setModal(null)}
        />
      )}
    </Page>
  );
}
