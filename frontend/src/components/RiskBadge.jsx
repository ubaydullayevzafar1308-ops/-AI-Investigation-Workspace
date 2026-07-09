const STYLES = {
  high: 'bg-red-500/15 text-red-400 border-red-500/30',
  medium: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  low: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
};

const LABELS = {
  high: 'Высокий',
  medium: 'Средний',
  low: 'Низкий',
};

export default function RiskBadge({ level, score }) {
  const style = STYLES[level] || STYLES.low;
  const label = LABELS[level] || level;

  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${style}`}>
      {label}
      {typeof score === 'number' && <span className="opacity-70">· {score}</span>}
    </span>
  );
}
