import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, X, Zap, ShoppingCart } from 'lucide-react';
import { useCredits } from '../../context/CreditContext';

export default function LowCreditBanner() {
  const { balance, isLow, isEmpty, allocated, pct } = useCredits();
  const [dismissed, setDismissed] = useState(false);
  const navigate = useNavigate();

  if ((!isLow && !isEmpty) || dismissed || balance === null) return null;

  return (
    <div className={`flex items-center gap-3 px-4 py-2.5 text-sm border-b shrink-0 ${
      isEmpty
        ? 'bg-red-50 border-red-200 text-red-800'
        : 'bg-amber-50 border-amber-200 text-amber-800'
    }`}>
      <AlertTriangle size={15} className="shrink-0" />

      <div className="flex-1 min-w-0">
        {isEmpty ? (
          <span>
            <strong>No credits remaining.</strong> Intents are queued but paused.
            Top up to resume execution.
          </span>
        ) : (
          <span>
            <strong>{balance} credits remaining</strong> ({Math.round(pct)}% of {allocated?.toLocaleString()} monthly allocation).
            Buy more to avoid interruption.
          </span>
        )}
      </div>

      <div className="flex items-center gap-2 shrink-0">
        <button
          onClick={() => navigate('/billing?tab=credits')}
          className={`flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors ${
            isEmpty
              ? 'bg-red-600 text-white hover:bg-red-700'
              : 'bg-amber-600 text-white hover:bg-amber-700'
          }`}
        >
          <ShoppingCart size={12} /> Top up credits
        </button>
        <button
          onClick={() => navigate('/billing')}
          className="text-xs underline opacity-70 hover:opacity-100"
        >
          Upgrade plan
        </button>
        <button
          onClick={() => setDismissed(true)}
          className="p-1 rounded hover:bg-black/10 transition-colors"
        >
          <X size={13} />
        </button>
      </div>
    </div>
  );
}
