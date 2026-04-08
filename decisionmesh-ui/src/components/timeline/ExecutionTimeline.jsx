import { useState, useEffect } from 'react';
import { CheckCircle2, Circle, Loader2, XCircle, Clock, DollarSign, Cpu, AlertTriangle } from 'lucide-react';
import { getIntentEvents } from '../../utils/api';
import { formatCost, formatDate, PHASE_ORDER, cn } from '../../lib/utils';
import { Spinner } from '../shared';

const STEPS = [
  { phase: 'CREATED',    label: 'Created',    desc: 'Intent registered' },
  { phase: 'PLANNING',   label: 'Planning',   desc: 'Building execution plan' },
  { phase: 'PLANNED',    label: 'Planned',    desc: 'Plan locked' },
  { phase: 'EXECUTING',  label: 'Executing',  desc: 'Dispatching to adapter' },
  { phase: 'EVALUATING', label: 'Evaluating', desc: 'Scoring output' },
  { phase: 'COMPLETED',  label: 'Completed',  desc: 'Terminal state' },
];

function stepStatus(phase, currentPhase, terminal, satisfied) {
  const order = PHASE_ORDER;
  const cur   = order.indexOf(currentPhase);
  const idx   = order.indexOf(phase);
  if (currentPhase === 'COMPLETED') {
    if (idx < order.length - 1) return 'done';
    return satisfied ? 'done' : 'failed';
  }
  if (idx < cur) return 'done';
  if (idx === cur) return 'active';
  return 'pending';
}

function StepIcon({ status }) {
  if (status === 'done')    return <CheckCircle2 size={18} className="text-green-500" />;
  if (status === 'active')  return <Loader2 size={18} className="text-blue-600 animate-spin" />;
  if (status === 'failed')  return <XCircle size={18} className="text-red-500" />;
  return <Circle size={18} className="text-slate-300" />;
}

function EventCard({ event }) {
  return (
    <div className="ml-7 mt-1.5 mb-3 bg-slate-50 border border-slate-100 rounded-lg p-3 text-xs space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="font-medium text-slate-700">{event.eventType?.replace(/_/g, ' ')}</span>
        <span className="text-slate-400 font-mono text-[10px]">{formatDate(event.occurredAt)}</span>
      </div>
      {(event.phaseFrom || event.phaseTo) && (
        <div className="flex items-center gap-2 text-slate-500">
          <span className="bg-slate-200 text-slate-600 px-1.5 py-0.5 rounded text-[10px] font-mono">{event.phaseFrom ?? '—'}</span>
          <span>→</span>
          <span className="bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded text-[10px] font-mono">{event.phaseTo ?? '—'}</span>
        </div>
      )}
      <div className="flex items-center gap-3 text-slate-400 flex-wrap">
        {event.adapterId && (
          <span className="flex items-center gap-1"><Cpu size={10} /><span className="font-mono text-[10px]">{event.adapterId.split('-')[0]}</span></span>
        )}
        {event.costUsdSnapshot != null && (
          <span className="flex items-center gap-1"><DollarSign size={10} />{formatCost(event.costUsdSnapshot)}</span>
        )}
        {event.riskScoreSnapshot != null && (
          <span className="flex items-center gap-1"><AlertTriangle size={10} />risk {event.riskScoreSnapshot.toFixed(3)}</span>
        )}
        {event.attemptNumber != null && (
          <span className="flex items-center gap-1"><Clock size={10} />attempt #{event.attemptNumber}</span>
        )}
      </div>
    </div>
  );
}

export default function ExecutionTimeline({ keycloak, intentId, currentPhase, terminal, satisfied }) {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!intentId) return;
    let active = true;

    async function load() {
      try {
        const data = await getIntentEvents(keycloak, intentId);
        if (active) setEvents(data ?? []);
      } catch { /* non-fatal */ }
      finally { if (active) setLoading(false); }
    }

    load();
    if (!terminal) {
      const t = setInterval(load, 4000);
      return () => { active = false; clearInterval(t); };
    }
    return () => { active = false; };
  }, [intentId, terminal, keycloak]);

  const byPhase = events.reduce((acc, e) => {
    const key = e.phaseTo ?? e.phaseFrom ?? 'CREATED';
    (acc[key] ??= []).push(e);
    return acc;
  }, {});

  if (loading) return <div className="flex justify-center py-8"><Spinner /></div>;

  return (
    <div className="relative space-y-0">
      {STEPS.map(({ phase, label, desc }, i) => {
        const status = stepStatus(phase, currentPhase, terminal, satisfied);
        const isLast = i === STEPS.length - 1;
        const phaseEvents = byPhase[phase] ?? [];

        return (
          <div key={phase} className="relative">
            {!isLast && (
              <div className={cn(
                'absolute left-[8px] top-[26px] w-px',
                phaseEvents.length > 0 ? 'bottom-0' : 'h-10',
                status === 'done' ? 'bg-green-200' : 'bg-slate-100'
              )} />
            )}
            <div className="flex items-start gap-3">
              <div className="shrink-0 mt-0.5 z-10">
                <StepIcon status={isLast && terminal && !satisfied ? 'failed' : status} />
              </div>
              <div className="flex-1 pb-1">
                <div className="flex items-center gap-2">
                  <span className={cn('text-sm font-medium', {
                    done:    'text-slate-800',
                    active:  'text-blue-700',
                    pending: 'text-slate-400',
                    failed:  'text-red-600',
                  }[status])}>
                    {label}
                  </span>
                  {status === 'active' && (
                    <span className="text-[10px] font-medium text-blue-600 bg-blue-50 px-1.5 py-0.5 rounded-full">In progress</span>
                  )}
                  {isLast && terminal && (
                    <span className={cn('text-[10px] font-medium px-1.5 py-0.5 rounded-full',
                      satisfied ? 'text-green-600 bg-green-50' : 'text-red-600 bg-red-50'
                    )}>
                      {satisfied ? 'Satisfied' : 'Violated'}
                    </span>
                  )}
                </div>
                <p className="text-xs text-slate-400 mt-0.5">{desc}</p>
              </div>
            </div>
            {phaseEvents.map(e => <EventCard key={e.id ?? e.eventId} event={e} />)}
            {phaseEvents.length === 0 && !isLast && <div className="h-3" />}
          </div>
        );
      })}
    </div>
  );
}
