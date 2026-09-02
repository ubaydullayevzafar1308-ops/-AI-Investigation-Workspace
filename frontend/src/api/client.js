const BASE = '/api';

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // ответ не JSON — оставляем стандартное сообщение статуса
    }
    throw new Error(message);
  }
  if (res.status === 204) return null;
  return res.json();
}

export const api = {
  listAlerts: (page = 0, size = 20) => request(`/alerts?page=${page}&size=${size}`),
  investigateAlert: (alertId) => request(`/alerts/${alertId}/investigate`, { method: 'POST' }),

  listCases: () => request('/cases'),
  getCase: (caseId) => request(`/cases/${caseId}`),
  getEvidence: (caseId) => request(`/cases/${caseId}/evidence`),
  getExplanation: (caseId) => request(`/cases/${caseId}/explanation`),
  getGraph: (caseId) => request(`/cases/${caseId}/graph`),
  getReport: (caseId) => request(`/cases/${caseId}/report`),
  getAudit: (caseId) => request(`/cases/${caseId}/audit`),
  decide: (caseId, status, comment) =>
    request(`/cases/${caseId}/decision`, {
      method: 'PATCH',
      body: JSON.stringify({ status, comment }),
    }),
  chat: (caseId, question) =>
    request(`/cases/${caseId}/chat`, {
      method: 'POST',
      body: JSON.stringify({ question }),
    }),

  getModules: () => request('/modules'),
  getDashboardMetrics: () => request('/dashboard/metrics'),
};
