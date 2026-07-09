export function Loading({ label = 'Загрузка...' }) {
  return (
    <div className="flex items-center gap-2 text-slate-400 text-sm py-8 justify-center">
      <span className="inline-block h-2 w-2 rounded-full bg-sky-500 animate-pulse" />
      {label}
    </div>
  );
}

export function ErrorMessage({ error, onRetry }) {
  return (
    <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      <p className="font-medium">Не удалось загрузить данные</p>
      <p className="mt-1 opacity-80">{error?.message || String(error)}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-2 text-xs underline underline-offset-2 hover:text-red-200"
        >
          Повторить
        </button>
      )}
    </div>
  );
}
