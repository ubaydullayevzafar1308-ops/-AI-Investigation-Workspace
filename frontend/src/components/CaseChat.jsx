import { useState } from 'react';
import { api } from '../api/client';

export default function CaseChat({ caseId }) {
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState([]);
  const [sending, setSending] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    const trimmed = question.trim();
    if (!trimmed || sending) return;

    setMessages((prev) => [...prev, { role: 'question', text: trimmed }]);
    setQuestion('');
    setSending(true);

    try {
      const { answer } = await api.chat(caseId, trimmed);
      setMessages((prev) => [...prev, { role: 'answer', text: answer }]);
    } catch (err) {
      setMessages((prev) => [...prev, { role: 'error', text: err.message }]);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 space-y-3 overflow-y-auto mb-3 min-h-[120px]">
        {messages.length === 0 && (
          <p className="text-sm text-slate-500">
            Задай вопрос по фактам этого кейса — ответ строится только на собранных данных.
          </p>
        )}
        {messages.map((msg, idx) => (
          <div
            key={idx}
            className={
              msg.role === 'question'
                ? 'text-sm text-slate-200 bg-slate-800/60 rounded-lg px-3 py-2 ml-auto max-w-[85%]'
                : msg.role === 'error'
                ? 'text-sm text-red-300 bg-red-500/10 rounded-lg px-3 py-2 max-w-[85%]'
                : 'text-sm text-slate-300 bg-slate-900/60 border border-slate-800 rounded-lg px-3 py-2 max-w-[85%]'
            }
          >
            {msg.text}
          </div>
        ))}
        {sending && <div className="text-xs text-slate-500">Думаю...</div>}
      </div>

      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Например: почему сработало правило R03?"
          className="flex-1 rounded-lg bg-slate-900 border border-slate-800 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-sky-600"
        />
        <button
          type="submit"
          disabled={sending || !question.trim()}
          className="rounded-lg bg-sky-600 hover:bg-sky-500 disabled:bg-slate-700 disabled:cursor-not-allowed px-4 py-2 text-sm font-medium text-white transition-colors"
        >
          Спросить
        </button>
      </form>
    </div>
  );
}
