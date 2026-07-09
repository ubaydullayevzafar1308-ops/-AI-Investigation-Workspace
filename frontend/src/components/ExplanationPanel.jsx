export default function ExplanationPanel({ explanation }) {
  if (!explanation || !explanation.reasons?.length) {
    return <p className="text-sm text-slate-500">Причин не найдено.</p>;
  }

  const maxContribution = Math.max(...explanation.reasons.map((r) => r.contribution), 1);

  return (
    <div className="space-y-3">
      {explanation.reasons.map((reason, idx) => (
        <div key={idx}>
          <div className="flex items-center justify-between gap-3 mb-1">
            <span className="text-sm font-medium text-slate-200">{reason.factor}</span>
            <span className="text-sm font-semibold text-red-400 shrink-0">+{reason.contribution}</span>
          </div>
          <div className="h-1.5 rounded-full bg-slate-800 overflow-hidden">
            <div
              className="h-full rounded-full bg-gradient-to-r from-red-600 to-amber-500"
              style={{ width: `${(reason.contribution / maxContribution) * 100}%` }}
            />
          </div>
          {reason.detail && <p className="text-xs text-slate-500 mt-1">{reason.detail}</p>}
        </div>
      ))}
    </div>
  );
}
