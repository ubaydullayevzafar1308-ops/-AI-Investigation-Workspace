package api

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/llm"
)

func (h *Handler) health(w http.ResponseWriter, r *http.Request) error {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	return nil
}

// ── alerts ───────────────────────────────────────────────────────────

type alertSummary struct {
	ID            int64     `json:"id"`
	TransactionID *int64    `json:"transactionId"`
	ClientID      *int64    `json:"clientId"`
	TriggerReason string    `json:"triggerReason"`
	Severity      string    `json:"severity"`
	Status        string    `json:"status"`
	CreatedAt     time.Time `json:"createdAt"`
}

func toAlertSummary(a domain.Alert) alertSummary {
	return alertSummary{
		ID: a.ID, TransactionID: a.TransactionID, ClientID: a.ClientID,
		TriggerReason: a.TriggerReason, Severity: a.Severity, Status: a.Status, CreatedAt: a.CreatedAt,
	}
}

func (h *Handler) listAlerts(w http.ResponseWriter, r *http.Request) error {
	pageNum, size, limit, offset := pageParams(r)
	alerts, total, err := h.q.SearchAlerts(r.Context(), optionalQuery(r, "status"), optionalQuery(r, "severity"), limit, offset)
	if err != nil {
		return err
	}
	out := make([]alertSummary, 0, len(alerts))
	for _, a := range alerts {
		out = append(out, toAlertSummary(a))
	}
	writeJSON(w, http.StatusOK, newPage(out, len(out), total, pageNum, size))
	return nil
}

func (h *Handler) investigate(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	ready, err := h.caseBuilder.BuildCase(r.Context(), id)
	if err != nil {
		return badRequest(err.Error())
	}
	writeJSON(w, http.StatusOK, ready)
	return nil
}

// ── cases ────────────────────────────────────────────────────────────

type caseSummary struct {
	ID              int64      `json:"id"`
	AlertID         *int64     `json:"alertId"`
	ClientID        *int64     `json:"clientId"`
	RiskScore       int        `json:"riskScore"`
	RiskLevel       string     `json:"riskLevel"`
	Status          string     `json:"status"`
	AnalystDecision *string    `json:"analystDecision"`
	CreatedAt       time.Time  `json:"createdAt"`
	ClosedAt        *time.Time `json:"closedAt"`
}

func toCaseSummary(c domain.Case) caseSummary {
	return caseSummary{
		ID: c.ID, AlertID: c.AlertID, ClientID: c.ClientID, RiskScore: c.RiskScore,
		RiskLevel: c.RiskLevel, Status: c.Status, AnalystDecision: c.AnalystDecision,
		CreatedAt: c.CreatedAt, ClosedAt: c.ClosedAt,
	}
}

type caseDetail struct {
	caseSummary
	Dossier     json.RawMessage `json:"dossier"`
	Evidence    json.RawMessage `json:"evidence"`
	Explanation json.RawMessage `json:"explanation"`
}

func rawOrNull(b []byte) json.RawMessage {
	if len(b) == 0 {
		return json.RawMessage("null")
	}
	return json.RawMessage(b)
}

func (h *Handler) listCases(w http.ResponseWriter, r *http.Request) error {
	pageNum, size, limit, offset := pageParams(r)
	cases, total, err := h.q.ListCasesPaged(r.Context(), limit, offset)
	if err != nil {
		return err
	}
	out := make([]caseSummary, 0, len(cases))
	for _, c := range cases {
		out = append(out, toCaseSummary(c))
	}
	writeJSON(w, http.StatusOK, newPage(out, len(out), total, pageNum, size))
	return nil
}

func (h *Handler) findCase(r *http.Request) (domain.Case, error) {
	id, err := pathInt64(r, "id")
	if err != nil {
		return domain.Case{}, err
	}
	c, err := h.q.GetCase(r.Context(), id)
	if err != nil {
		return domain.Case{}, notFound("Case not found")
	}
	return c, nil
}

func (h *Handler) getCase(w http.ResponseWriter, r *http.Request) error {
	c, err := h.findCase(r)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, caseDetail{
		caseSummary: toCaseSummary(c),
		Dossier:     rawOrNull(c.DossierJSON),
		Evidence:    rawOrNull(c.EvidenceJSON),
		Explanation: rawOrNull(c.ExplanationJSON),
	})
	return nil
}

func (h *Handler) getEvidence(w http.ResponseWriter, r *http.Request) error {
	c, err := h.findCase(r)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write([]byte(rawOrNull(c.EvidenceJSON)))
	return nil
}

func (h *Handler) getExplanation(w http.ResponseWriter, r *http.Request) error {
	c, err := h.findCase(r)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write([]byte(rawOrNull(c.ExplanationJSON)))
	return nil
}

func (h *Handler) getGraph(w http.ResponseWriter, r *http.Request) error {
	c, err := h.findCase(r)
	if err != nil {
		return err
	}
	if c.ClientID == nil {
		return errStatus(http.StatusConflict, "case has no client")
	}
	g, err := h.graph.Build(r.Context(), h.q, *c.ClientID)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, g)
	return nil
}

type decisionRequest struct {
	Status  string `json:"status"`
	Comment string `json:"comment"`
}

func (h *Handler) decide(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	var req decisionRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	if _, err := h.q.GetCase(r.Context(), id); err != nil {
		return notFound("Case not found")
	}
	tx, err := h.pool.Begin(r.Context())
	if err != nil {
		return err
	}
	defer tx.Rollback(r.Context())
	q := h.q.WithTx(tx)

	updated, err := q.UpdateCaseDecision(r.Context(), id, req.Status, req.Comment)
	if err != nil {
		return err
	}
	// Имя аналитика пока не приходит через auth-контекст — placeholder.
	if err := h.audit.LogDecision(r.Context(), q, id, "analyst"); err != nil {
		return err
	}
	if err := tx.Commit(r.Context()); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, toCaseSummary(updated))
	return nil
}

// ── chat ─────────────────────────────────────────────────────────────

type chatRequest struct {
	Question string `json:"question"`
}
type chatResponse struct {
	Answer string `json:"answer"`
}

func (h *Handler) chat(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	var req chatRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	c, err := h.q.GetCase(r.Context(), id)
	if err != nil {
		return notFound("Case not found")
	}
	if len(c.DossierJSON) == 0 || len(c.EvidenceJSON) == 0 || len(c.ExplanationJSON) == 0 {
		return errStatus(http.StatusConflict, "Case has no stored snapshot yet — was it fully built?")
	}

	var dossier domain.Dossier
	var bundle domain.EvidenceBundle
	var explanation domain.Explanation
	if err := json.Unmarshal(c.DossierJSON, &dossier); err != nil {
		return err
	}
	if err := json.Unmarshal(c.EvidenceJSON, &bundle); err != nil {
		return err
	}
	if err := json.Unmarshal(c.ExplanationJSON, &explanation); err != nil {
		return err
	}

	risk := domain.RiskResult{Score: c.RiskScore, Level: c.RiskLevel}
	safe := llm.BuildSafeJSON(risk, explanation, bundle, dossier)
	answer := h.llm.AnswerQuestion(r.Context(), h.q, c.ID, safe, req.Question)

	writeJSON(w, http.StatusOK, chatResponse{Answer: answer})
	return nil
}

// ── reports ──────────────────────────────────────────────────────────

func (h *Handler) getReport(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	rep, err := h.q.GetReportByCase(r.Context(), id)
	if err != nil {
		return notFound("No report for case")
	}
	writeJSON(w, http.StatusOK, rep)
	return nil
}

type reportEditRequest struct {
	FinalText  string `json:"finalText"`
	ApprovedBy string `json:"approvedBy"`
}

func (h *Handler) updateReport(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	var req reportEditRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	rep, err := h.reportGen.SaveFinalEdit(r.Context(), h.q, id, req.FinalText, req.ApprovedBy)
	if err != nil {
		return badRequest(err.Error())
	}
	writeJSON(w, http.StatusOK, rep)
	return nil
}

// ── audit ────────────────────────────────────────────────────────────

const auditMaxLen = 200

type auditSummary struct {
	ID           int64     `json:"id"`
	EventType    string    `json:"eventType"`
	RulesVersion *string   `json:"rulesVersion"`
	RiskScore    *int      `json:"riskScore"`
	LLMProvider  *string   `json:"llmProvider"`
	LLMModel     *string   `json:"llmModel"`
	LLMPrompt    *string   `json:"llmPrompt"`
	LLMResponse  *string   `json:"llmResponse"`
	Actor        *string   `json:"actor"`
	CreatedAt    time.Time `json:"createdAt"`
}

type auditDetail struct {
	ID           int64     `json:"id"`
	CaseID       *int64    `json:"caseId"`
	EventType    string    `json:"eventType"`
	RulesVersion *string   `json:"rulesVersion"`
	RiskScore    *int      `json:"riskScore"`
	LLMProvider  *string   `json:"llmProvider"`
	LLMModel     *string   `json:"llmModel"`
	LLMPrompt    *string   `json:"llmPrompt"`
	LLMResponse  *string   `json:"llmResponse"`
	Actor        *string   `json:"actor"`
	CreatedAt    time.Time `json:"createdAt"`
}

func truncatePtr(s *string) *string {
	if s == nil {
		return nil
	}
	r := []rune(*s)
	if len(r) <= auditMaxLen {
		return s
	}
	t := string(r[:auditMaxLen]) + "…"
	return &t
}

func (h *Handler) getAudit(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	entries, err := h.q.ListAuditByCase(r.Context(), id)
	if err != nil {
		return err
	}
	out := make([]auditSummary, 0, len(entries))
	for _, a := range entries {
		out = append(out, auditSummary{
			ID: a.ID, EventType: a.EventType, RulesVersion: a.RulesVersion, RiskScore: a.RiskScore,
			LLMProvider: a.LLMProvider, LLMModel: a.LLMModel,
			LLMPrompt: truncatePtr(a.LLMPrompt), LLMResponse: truncatePtr(a.LLMResponse),
			Actor: a.Actor, CreatedAt: a.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

func (h *Handler) getAuditEvent(w http.ResponseWriter, r *http.Request) error {
	id, err := pathInt64(r, "id")
	if err != nil {
		return err
	}
	eventID, err := pathInt64(r, "eventId")
	if err != nil {
		return err
	}
	a, err := h.q.GetAuditByIDAndCase(r.Context(), eventID, id)
	if err != nil {
		return notFound("Audit event not found for case")
	}
	writeJSON(w, http.StatusOK, auditDetail{
		ID: a.ID, CaseID: a.CaseID, EventType: a.EventType, RulesVersion: a.RulesVersion,
		RiskScore: a.RiskScore, LLMProvider: a.LLMProvider, LLMModel: a.LLMModel,
		LLMPrompt: a.LLMPrompt, LLMResponse: a.LLMResponse, Actor: a.Actor, CreatedAt: a.CreatedAt,
	})
	return nil
}

// ── modules & dashboard ──────────────────────────────────────────────

func (h *Handler) listModules(w http.ResponseWriter, r *http.Request) error {
	flags, err := h.q.ListFeatureFlags(r.Context())
	if err != nil {
		return err
	}
	if flags == nil {
		flags = []domain.FeatureFlag{}
	}
	writeJSON(w, http.StatusOK, flags)
	return nil
}

type dashboardMetrics struct {
	TotalCases          int64            `json:"totalCases"`
	CasesToday          int64            `json:"casesToday"`
	AvgRiskScore        *float64         `json:"avgRiskScore"`
	RuleDistribution    map[string]int64 `json:"ruleDistribution"`
	EstimatedHoursSaved float64          `json:"estimatedHoursSaved"`
}

func (h *Handler) dashboardMetrics(w http.ResponseWriter, r *http.Request) error {
	ctx := r.Context()
	total, err := h.q.CountCases(ctx)
	if err != nil {
		return err
	}
	startOfDay := time.Now().UTC().Truncate(24 * time.Hour)
	today, err := h.q.CountCasesSince(ctx, startOfDay)
	if err != nil {
		return err
	}
	avg, err := h.q.AverageRiskScore(ctx)
	if err != nil {
		return err
	}
	dist, err := h.q.CountByRuleCode(ctx)
	if err != nil {
		return err
	}
	if dist == nil {
		dist = map[string]int64{}
	}
	writeJSON(w, http.StatusOK, dashboardMetrics{
		TotalCases:          total,
		CasesToday:          today,
		AvgRiskScore:        avg,
		RuleDistribution:    dist,
		EstimatedHoursSaved: float64(total) * h.hoursSavedPerCase,
	})
	return nil
}
