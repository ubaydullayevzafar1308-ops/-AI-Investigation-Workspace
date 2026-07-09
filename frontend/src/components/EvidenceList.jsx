const TYPE_LABELS = {
  rule_hit: 'Правило',
  relation: 'Связь',
  suspicious_tx: 'Подозрительная операция',
  previous_alert: 'Прошлый алерт',
  anomaly: 'Аномалия',
  shared_device: 'Общее устройство',
};

const TYPE_STYLES = {
  rule_hit: 'border-red-500/30 bg-red-500/5',
  shared_device: 'border-amber-500/30 bg-amber-500/5',
  suspicious_tx: 'border-amber-500/30 bg-amber-500/5',
};

function formatDetails(details) {
  if (!details || Object.keys(details).length === 0) return null;
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
    .join(' · ');
}

export default function EvidenceList({ evidence }) {
  if (!evidence || !evidence.items?.length) {
    return <p className="text-sm text-slate-500">Доказательств не найдено.</p>;
  }

  return (
    <div className="space-y-2">
      {evidence.items.map((item, idx) => (
        <div
          key={idx}
          className={`rounded-lg border px-4 py-3 ${TYPE_STYLES[item.type] || 'border-slate-800 bg-slate-900/40'}`}
        >
          <div className="flex items-center justify-between gap-3">
            <span className="text-sm font-medium text-slate-200">{item.title}</span>
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-[11px] uppercase tracking-wide text-slate-500">
                {TYPE_LABELS[item.type] || item.type}
              </span>
              {item.weight > 0 && (
                <span className="text-xs font-semibold text-red-400">+{item.weight}</span>
              )}
            </div>
          </div>
          {formatDetails(item.details) && (
            <p className="text-xs text-slate-500 mt-1 break-words">{formatDetails(item.details)}</p>
          )}
        </div>
      ))}
    </div>
  );
}
