import { useState, useEffect } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { TrendingUp, AlertTriangle } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Spinner, cn } from '../components/shared';
import { getDriftData } from '../utils/api';
import { formatCost, formatLatency } from '../lib/utils';

function DriftBar({ value }) {
  const pct = Math.min(100, (value ?? 0) * 100);
  const color = pct > 70 ? 'bg-red-500' : pct > 40 ? 'bg-amber-500' : 'bg-green-500';
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }}/>
      </div>
      <span className="text-xs tabular-nums text-slate-600 w-10 text-right">{(value ?? 0).toFixed(3)}</span>
    </div>
  );
}

export default function DriftDashboard({ keycloak }) {
  const [data, setData]     = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getDriftData(keycloak).then(d => setData(d ?? [])).finally(() => setLoading(false));
  }, [keycloak]);

  const highDrift = data.filter(a => (a.driftScore ?? 0) > 0.7);

  if (loading) return (
    <Page title="Drift Dashboard"><div className="flex justify-center py-24"><Spinner className="w-8 h-8"/></div></Page>
  );

  return (
    <Page title="Drift Dashboard" subtitle="Monitor adapter performance and drift">
      {highDrift.length > 0 && (
        <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-3">
          <AlertTriangle size={16} className="text-amber-600 shrink-0 mt-0.5"/>
          <div>
            <p className="text-sm font-medium text-amber-800">High drift detected</p>
            <p className="text-xs text-amber-700 mt-0.5">
              {highDrift.map(a => a.adapterName).join(', ')} {highDrift.length === 1 ? 'has' : 'have'} drift score above 0.7
            </p>
          </div>
        </div>
      )}

      {/* Adapter comparison table */}
      <Card>
        <CardHeader><CardTitle>Adapter comparison</CardTitle></CardHeader>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['Adapter', 'Drift score', 'Performance', 'Failure rate', 'Avg cost', 'Avg latency'].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.length === 0 && (
                <tr><td colSpan={6} className="px-5 py-12 text-center text-sm text-slate-400">No drift data available</td></tr>
              )}
              {data.map(a => (
                <tr key={a.adapterId} className="border-b border-slate-50">
                  <td className="px-5 py-4">
                    <p className="text-sm font-medium text-slate-800">{a.adapterName}</p>
                    <p className="text-xs text-slate-400 font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>{a.adapterId?.split('-')[0]}</p>
                  </td>
                  <td className="px-5 py-4 w-40"><DriftBar value={a.driftScore}/></td>
                  <td className="px-5 py-4 w-40"><DriftBar value={a.performanceScore}/></td>
                  <td className="px-5 py-4 text-xs tabular-nums text-slate-700">{((a.failureRate ?? 0) * 100).toFixed(1)}%</td>
                  <td className="px-5 py-4 text-xs tabular-nums text-slate-700">{formatCost(a.avgCost)}</td>
                  <td className="px-5 py-4 text-xs tabular-nums text-slate-700">{formatLatency(a.avgLatency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Trend charts */}
      {data.filter(a => a.trend?.length > 0).map(a => (
        <Card key={a.adapterId}>
          <CardHeader>
            <div className="flex items-center gap-2">
              <TrendingUp size={13} className="text-slate-400"/>
              <CardTitle>{a.adapterName} — drift trend</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={160}>
              <LineChart data={a.trend}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9"/>
                <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
                <YAxis domain={[0, 1]} tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
                <Tooltip/>
                <Line type="monotone" dataKey="totalCost" stroke="#2563EB" strokeWidth={2} dot={false}/>
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      ))}
    </Page>
  );
}
