import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import StatusBadge from '../components/StatusBadge';
import { Loading, ErrorMessage } from '../components/StatusHelpers';

const SEVERITY_LABELS = { low: 'Низкая', medium: 'Средняя', high: 'Высокая' };
const SEVERITY_STYLES = {
  low: 'text-emerald-400',
  medium: 'text-amber-400',
  high: 'text-red-400',
};

export default function AlertsList() {
  const { data: page, loading, error, refetch } = useApi(api.listAlerts, []);
  const alerts = page?.content;
  const [investigatingId, setInvestigatingId] = useState(null);
  const navigate = useNavigate();

  async function handleInvestigate(alertId) {
    setInvestigatingId(alertId);
    try {
      const readyCase = await api.investigateAlert(alertId);
      navigate(`/cases/${readyCase.caseId}`);
    } catch (err) {
      alert(`Не удалось запустить расследование: ${err.message}`);
    } finally {
      setInvestigatingId(null);
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-6 py-10">
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-slate-100">Алерты</h1>
        <p className="text-slate-400 text-sm mt-1">
          Сигналы от системы мониторинга. Запусти расследование, чтобы получить готовый Case.
        </p>
      </header>

      {loading && <Loading label="Загружаем алерты..." />}
      {error && <ErrorMessage error={error} onRetry={refetch} />}

      {alerts && alerts.length === 0 && (
        <p className="text-slate-500 text-sm">
          Алертов нет. Если база пустая — запусти seed-профиль (см. README).
        </p>
      )}

      {alerts && alerts.length > 0 && (
        <div className="rounded-xl border border-slate-800 bg-slate-900/50 divide-y divide-slate-800 overflow-hidden">
          {alerts.map((alert) => (
            <div key={alert.id} className="flex items-center justify-between gap-4 px-5 py-4">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-slate-200 font-medium">Alert #{alert.id}</span>
                  <StatusBadge status={alert.status} />
                  <span className={`text-xs font-medium ${SEVERITY_STYLES[alert.severity] || 'text-slate-400'}`}>
                    {SEVERITY_LABELS[alert.severity] || alert.severity}
                  </span>
                </div>
                <p className="text-sm text-slate-500 mt-0.5 truncate">{alert.triggerReason}</p>
              </div>

              <button
                onClick={() => handleInvestigate(alert.id)}
                disabled={investigatingId === alert.id}
                className="shrink-0 rounded-lg bg-sky-600 hover:bg-sky-500 disabled:bg-slate-700 disabled:cursor-not-allowed px-4 py-2 text-sm font-medium text-white transition-colors"
              >
                {investigatingId === alert.id ? 'Собираем кейс...' : 'Расследовать'}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
