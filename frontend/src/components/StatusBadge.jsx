const STYLES = {
  new: 'bg-sky-500/15 text-sky-400 border-sky-500/30',
  investigating: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  closed: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
  open: 'bg-sky-500/15 text-sky-400 border-sky-500/30',
  approved: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  rejected: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
  escalated: 'bg-red-500/15 text-red-400 border-red-500/30',
};

const LABELS = {
  new: 'Новый',
  investigating: 'В работе',
  closed: 'Закрыт',
  open: 'Открыт',
  approved: 'Одобрен',
  rejected: 'Отклонён',
  escalated: 'Эскалирован',
};

export default function StatusBadge({ status }) {
  const style = STYLES[status] || 'bg-slate-500/15 text-slate-400 border-slate-500/30';
  const label = LABELS[status] || status;

  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium ${style}`}>
      {label}
    </span>
  );
}
