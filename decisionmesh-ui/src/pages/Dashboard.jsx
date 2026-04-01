import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Activity, DollarSign, Zap, CheckCircle, Clock, Cpu, Server } from 'lucide-react';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, MetricCard, PhaseBadge, SatisfactionBadge, Spinner } from '../components/shared';
import { listIntents, getCostAnalytics } from '../utils/api';
import { formatCost, formatDate, formatRelative, shortId } from '../lib/utils';

function ChartTip({ active, payload, label, fmt }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-slate-200 rounded-lg shadow-md p-3 text-xs">
      <p className="text-slate-400 mb-1">{label}</p>
      <p className="font-semibold text-slate-800">{fmt ? fmt(payload[0].value) : payload[0].value}</p>
    </div>
  );
}

export default function Dashboard({ keycloak }) {
  const navigate = useNavigate();
  const { balance, allocated, pct, statusColor, isLow, isEmpty } = useCredits();
  const [intents, setIntents]   = useState(null);
  const [costData, setCostData] = useState(null);
  const [loading, setLoading]   = useState(true);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const [i, c] = await Promise.allSettled([
          listIntents(keycloak, { size: 8, sort: 'createdAt,desc' }),
          getCostAnalytics(keycloak),
        ]);
        if (!active) return;
        if (i.status === 'fulfilled') setIntents(i.value);
        if (c.status === 'fulfilled') setCostData(c.value);
      } finally { if (active) setLoading(false); }
    }
    load();
    const t = setInterval(load, 30_000);
    return () => { active = false; clearInterval(t); };
  }, [keycloak]);

  const total    = intents?.totalElements ?? 0;
  const content  = intents?.content ?? [];
  const satisfied = content.filter(i => i.satisfactionState === 'SATISFIED').length;
  const rate      = content.length ? ((satisfied / content.length) * 100).toFixed(0) : '—';

  return (
    <Page title="Dashboard" subtitle="System overview">
      {/* KPIs */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <MetricCard label="Total intents"  value={total}             sub="All time"       icon={<Activity size={15}/>} />
        <MetricCard label="Total cost"     value={formatCost(costData?.totalCostUsd ?? 0)} sub="All time" icon={<DollarSign size={15}/>} />
        <MetricCard label="Success rate"   value={`${rate}%`}        sub="Recent intents" icon={<CheckCircle size={15}/>} />
        <MetricCard label="Avg cost"       value={formatCost(costData?.avgCostPerIntent ?? 0)} sub="Per intent" icon={<Zap size={15}/>} />
        {/* Credit balance card */}
        <div
          onClick={() => navigate('/billing')}
          className="bg-white rounded-xl border shadow-sm p-4 cursor-pointer hover:shadow-md transition-all"
          style={{ borderColor: isEmpty ? '#fca5a5' : isLow ? '#fcd34d' : '#e2e8f0' }}
        >
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs font-medium text-slate-500">Credits</p>
            <Zap size={15} style={{ color: statusColor }} />
          </div>
          <p className="text-2xl font-bold" style={{ color: statusColor }}>
            {balance === null ? '—' : balance?.toLocaleString()}
          </p>
          <div className="mt-2 h-1.5 bg-slate-100 rounded-full overflow-hidden">
            <div className="h-full rounded-full transition-all"
              style={{ width: `${pct}%`, backgroundColor: statusColor }} />
          </div>
          <p className="text-xs text-slate-400 mt-1.5">
            {isEmpty ? 'Top up now' : isLow ? 'Running low' : `of ${allocated?.toLocaleString()} mo`}
          </p>
        </div>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>Cost over time</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={costData?.costOverTime ?? []}>
                <defs>
                  <linearGradient id="cg" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#2563EB" stopOpacity={0.12}/>
                    <stop offset="95%" stopColor="#2563EB" stopOpacity={0}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9"/>
                <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(3)}`}/>
                <Tooltip content={<ChartTip fmt={v => formatCost(v)}/>}/>
                <Area type="monotone" dataKey="totalCost" stroke="#2563EB" strokeWidth={2} fill="url(#cg)"/>
              </AreaChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>Cost by adapter</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={costData?.costByAdapter ?? []} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" horizontal={false}/>
                <XAxis type="number" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(2)}`}/>
                <YAxis type="category" dataKey="adapterName" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={72}/>
                <Tooltip content={<ChartTip fmt={v => formatCost(v)}/>}/>
                <Bar dataKey="totalCost" fill="#2563EB" radius={[0, 4, 4, 0]}/>
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Recent intents table */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Recent intents</CardTitle>
          <button onClick={() => navigate('/intents')} className="text-xs text-blue-600 hover:text-blue-700 font-medium">View all →</button>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['ID', 'Type', 'Phase', 'Satisfaction', 'Cost', 'Created'].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={6} className="py-12 text-center"><Spinner className="mx-auto"/></td></tr>
              )}
              {!loading && content.length === 0 && (
                <tr><td colSpan={6} className="py-12 text-center text-sm text-slate-400">
                  No intents yet — <button onClick={() => navigate('/playground')} className="text-blue-600 hover:underline">submit one</button>
                </td></tr>
              )}
              {content.map(intent => (
                <tr key={intent.id}
                  className="border-b border-slate-50 hover:bg-blue-50/30 cursor-pointer transition-colors"
                  onClick={() => navigate(`/intents/${intent.id}`)}>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">{shortId(intent.id)}</span>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-700">{intent.intentType}</td>
                  <td className="px-5 py-3"><PhaseBadge phase={intent.phase}/></td>
                  <td className="px-5 py-3"><SatisfactionBadge state={intent.satisfactionState}/></td>
                  <td className="px-5 py-3 text-xs tabular-nums text-slate-700">{formatCost(intent.budget?.spentUsd ?? 0)}</td>
                  <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(intent.createdAt)}>{formatRelative(intent.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Status indicators */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {[
          { label: 'API Gateway',      icon: <Server size={14}/> },
          { label: 'Redis Cache',      icon: <Cpu size={14}/> },
          { label: 'Execution Engine', icon: <Clock size={14}/> },
        ].map(({ label, icon }) => (
          <Card key={label} className="p-4 flex items-center gap-3">
            <div className="p-2 rounded-lg bg-green-50 text-green-600">{icon}</div>
            <div>
              <p className="text-sm font-medium text-slate-700">{label}</p>
              <p className="text-xs text-green-600">Operational</p>
            </div>
            <div className="ml-auto w-2 h-2 rounded-full bg-green-500 animate-pulse"/>
          </Card>
        ))}
      </div>
    </Page>
  );
}
