import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import RiskBadge from '../components/RiskBadge';
import StatusBadge from '../components/StatusBadge';
import EvidenceList from '../components/EvidenceList';
import ExplanationPanel from '../components/ExplanationPanel';
import GraphView from '../components/GraphView';
import CaseChat from '../components/CaseChat';
import { Loading, ErrorMessage } from '../components/StatusHelpers';

const TABS = [
  { key: 'evidence', label: 'Доказательства' },
  { key: 'explanation', label: 'Объяснение' },
  { key: 'graph', label: 'Граф связей' },
  { key: 'report', label: 'Отчёт' },
  { key: 'audit', label: 'Аудит' },
];

export default function CaseDetail() {
  const { caseId } = useParams();
  const [activeTab, setActiveTab] = useState('evidence');
  const [decisionComment, setDecisionComment] = useState('');
  const [deciding, setDeciding] = useState(false);

  const { data: caseData, loading, error, refetch } = useApi(() => api.getCase(caseId), [caseId]);

  const tabData = useTabData(activeTab, caseId);

  async function handleDecision(status) {
    setDeciding(true);
    try {
      await api.decide(caseId, status, decisionComment);
      refetch();
    } catch (err) {
      alert(`Не удалось сохранить решение: ${err.message}`);
    } finally {
      setDeciding(false);
    }
  }

  if (loading) return <Loading label="Загружаем кейс..." />;
  if (error) return <div className="max-w-5xl mx-auto px-6 py-10"><ErrorMessage error={error} onRetry={refetch} /></div>;
  if (!caseData) return null;

  return (
    <div className="max-w-6xl mx-auto px-6 py-10">
      <Link to="/" className="text-sm text-slate-500 hover:text-slate-300 mb-4 inline-block">
        ← Все алерты
      </Link>

      <header className="flex items-start justify-between gap-6 mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-slate-100">Case #{caseData.id}</h1>
          <p className="text-slate-500 text-sm mt-1">
            Клиент #{caseData.clientId} · Alert #{caseData.alertId}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <StatusBadge status={caseData.status} />
          <RiskBadge level={caseData.riskLevel} score={caseData.riskScore} />
        </div>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-1 flex gap-1">
            {TABS.map((tab) => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`flex-1 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  activeTab === tab.key
                    ? 'bg-sky-600 text-white'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-5">
            {tabData.loading && <Loading />}
            {tabData.error && <ErrorMessage error={tabData.error} onRetry={tabData.refetch} />}
            {!tabData.loading && !tabData.error && (
              <TabContent activeTab={activeTab} data={tabData.data} />
            )}
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-5">
            <h2 className="text-sm font-semibold text-slate-300 mb-3">Решение аналитика</h2>
            <textarea
              value={decisionComment}
              onChange={(e) => setDecisionComment(e.target.value)}
              placeholder="Комментарий к решению (необязательно)"
              rows={2}
              className="w-full rounded-lg bg-slate-950 border border-slate-800 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-sky-600 mb-3"
            />
            <div className="flex gap-2">
              <button
                onClick={() => handleDecision('approved')}
                disabled={deciding}
                className="rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 px-4 py-2 text-sm font-medium text-white transition-colors"
              >
                Одобрить
              </button>
              <button
                onClick={() => handleDecision('escalated')}
                disabled={deciding}
                className="rounded-lg bg-red-600 hover:bg-red-500 disabled:opacity-50 px-4 py-2 text-sm font-medium text-white transition-colors"
              >
                Эскалировать
              </button>
              <button
                onClick={() => handleDecision('rejected')}
                disabled={deciding}
                className="rounded-lg bg-slate-700 hover:bg-slate-600 disabled:opacity-50 px-4 py-2 text-sm font-medium text-white transition-colors"
              >
                Отклонить (ложное срабатывание)
              </button>
            </div>
          </div>
        </div>

        <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-5 h-[560px] flex flex-col">
          <h2 className="text-sm font-semibold text-slate-300 mb-3">Спросить AI о кейсе</h2>
          <CaseChat caseId={caseId} />
        </div>
      </div>
    </div>
  );
}

function useTabData(activeTab, caseId) {
  const fetchers = {
    evidence: () => api.getEvidence(caseId),
    explanation: () => api.getExplanation(caseId),
    graph: () => api.getGraph(caseId),
    report: () => api.getReport(caseId),
    audit: () => api.getAudit(caseId),
  };
  return useApi(fetchers[activeTab], [activeTab, caseId]);
}

function TabContent({ activeTab, data }) {
  switch (activeTab) {
    case 'evidence':
      return <EvidenceList evidence={data} />;
    case 'explanation':
      return <ExplanationPanel explanation={data} />;
    case 'graph':
      return <GraphView graph={data} />;
    case 'report':
      return <ReportView report={data} />;
    case 'audit':
      return <AuditView entries={data} />;
    default:
      return null;
  }
}

function ReportView({ report }) {
  if (!report) return <p className="text-sm text-slate-500">Отчёт ещё не сформирован.</p>;
  return (
    <div className="space-y-3">
      <div>
        <h3 className="text-xs uppercase tracking-wide text-slate-500 mb-1">Черновик (LLM)</h3>
        <p className="text-sm text-slate-300 whitespace-pre-wrap">{report.draftText}</p>
      </div>
      {report.finalText && (
        <div>
          <h3 className="text-xs uppercase tracking-wide text-slate-500 mb-1">Итоговая версия</h3>
          <p className="text-sm text-slate-300 whitespace-pre-wrap">{report.finalText}</p>
        </div>
      )}
    </div>
  );
}

function AuditView({ entries }) {
  if (!entries?.length) return <p className="text-sm text-slate-500">Записей аудита нет.</p>;
  return (
    <div className="space-y-2">
      {entries.map((entry) => (
        <div key={entry.id} className="text-xs border-l-2 border-slate-800 pl-3 py-1">
          <div className="flex items-center gap-2 text-slate-400">
            <span className="font-mono">{new Date(entry.createdAt).toLocaleString('ru-RU')}</span>
            <span className="text-slate-600">·</span>
            <span className="font-medium text-slate-300">{entry.eventType}</span>
            {entry.riskScore != null && <span className="text-slate-500">score={entry.riskScore}</span>}
          </div>
          {entry.llmProvider && (
            <p className="text-slate-600 mt-0.5">
              LLM: {entry.llmProvider}/{entry.llmModel}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
