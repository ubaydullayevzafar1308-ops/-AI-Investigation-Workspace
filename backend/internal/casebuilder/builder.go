// Package casebuilder — Case Builder (⭐ центр системы). Оркестратор:
// не содержит бизнес-логики анализа, запускает пайплайн и передаёт
// контекст от этапа к этапу. См. ARCHITECTURE.md §5.
//
// Строгий порядок: Data Collector -> Rule Engine -> Graph Engine ->
// Evidence Collector -> Risk Engine -> Explainability -> Safe JSON ->
// AI Adapter -> Report Generator -> Ready Case.
package casebuilder

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/tiredjon/cbu/backend/internal/audit"
	"github.com/tiredjon/cbu/backend/internal/datacollector"
	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/evidence"
	"github.com/tiredjon/cbu/backend/internal/explain"
	"github.com/tiredjon/cbu/backend/internal/graph"
	"github.com/tiredjon/cbu/backend/internal/llm"
	"github.com/tiredjon/cbu/backend/internal/repo"
	"github.com/tiredjon/cbu/backend/internal/report"
	"github.com/tiredjon/cbu/backend/internal/risk"
	"github.com/tiredjon/cbu/backend/internal/rules"
)

type Service struct {
	pool      *pgxpool.Pool
	collector *datacollector.Service
	graph     *graph.Engine
	rules     *rules.Engine
	llm       *llm.Service
	reportGen *report.Service
	audit     *audit.Service
}

func New(pool *pgxpool.Pool, collector *datacollector.Service, graphEngine *graph.Engine,
	ruleEngine *rules.Engine, llmSvc *llm.Service, reportGen *report.Service, auditSvc *audit.Service) *Service {
	return &Service{
		pool: pool, collector: collector, graph: graphEngine, rules: ruleEngine,
		llm: llmSvc, reportGen: reportGen, audit: auditSvc,
	}
}

// BuildCase превращает алерт в готовый Case. Идемпотентно: если кейс
// для алерта уже собран, возвращает его без пересчёта и без вызова LLM.
func (s *Service) BuildCase(ctx context.Context, alertID int64) (domain.ReadyCase, error) {
	poolQ := repo.New(s.pool)

	alert, err := poolQ.GetAlert(ctx, alertID)
	if err != nil {
		return domain.ReadyCase{}, fmt.Errorf("alert not found: %d", alertID)
	}
	if alert.ClientID == nil {
		return domain.ReadyCase{}, fmt.Errorf("alert %d has no associated client", alertID)
	}

	if existing, err := poolQ.GetCaseByAlert(ctx, alertID); err == nil {
		return s.mapExistingCase(ctx, poolQ, existing)
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return domain.ReadyCase{}, err
	}
	defer tx.Rollback(ctx)
	q := repo.New(tx)

	clientID := *alert.ClientID
	caseID, createdAt, err := q.InsertCase(ctx, alertID, clientID)
	if err != nil {
		return domain.ReadyCase{}, err
	}
	if err := q.UpdateAlertStatus(ctx, alertID, domain.AlertStatusInvestigating); err != nil {
		return domain.ReadyCase{}, err
	}

	// ① Data Collector (внутри уже прогоняет CycleDetector для R03)
	dossier, err := s.collector.Collect(ctx, q, alertID)
	if err != nil {
		return domain.ReadyCase{}, err
	}
	if err := s.audit.Log(ctx, q, caseID, domain.EventCaseCreated, nil); err != nil {
		return domain.ReadyCase{}, err
	}

	// ② Rule Engine (+ персистенция rule_hits — источник правды причин score)
	ruleHits := s.rules.RunAll(dossier)
	if err := s.persistRuleHits(ctx, q, caseID, ruleHits); err != nil {
		return domain.ReadyCase{}, err
	}
	if err := s.audit.Log(ctx, q, caseID, domain.EventRulesExecuted, nil); err != nil {
		return domain.ReadyCase{}, err
	}

	// ③ Graph Engine (полный граф для фронта; циклы для R03 уже посчитаны)
	if _, err := s.graph.Build(ctx, q, dossier.ClientID); err != nil {
		return domain.ReadyCase{}, err
	}
	if err := s.audit.Log(ctx, q, caseID, domain.EventGraphBuilt, nil); err != nil {
		return domain.ReadyCase{}, err
	}

	// ④ Evidence Collector
	bundle := evidence.Collect(dossier.ClientID, ruleHits, dossier)
	if err := s.audit.Log(ctx, q, caseID, domain.EventEvidenceCollected, nil); err != nil {
		return domain.ReadyCase{}, err
	}

	// ⑤ Risk Engine
	riskResult := risk.Score(bundle)
	if err := s.audit.Log(ctx, q, caseID, domain.EventRiskScored, &riskResult.Score); err != nil {
		return domain.ReadyCase{}, err
	}

	// ⑥ Explainability Engine
	explanation := explain.Explain(riskResult, bundle)
	if err := s.audit.Log(ctx, q, caseID, domain.EventExplained, &riskResult.Score); err != nil {
		return domain.ReadyCase{}, err
	}

	// ⑦ Safe JSON  ⑧ LLM  ⑨ Report
	safe := llm.BuildSafeJSON(riskResult, explanation, bundle, dossier)
	humanExplanation := s.llm.ExplainRisk(ctx, q, caseID, safe)
	reportDraft := s.llm.GenerateReport(ctx, q, caseID, safe)
	if err := s.reportGen.SaveDraft(ctx, q, caseID, reportDraft); err != nil {
		return domain.ReadyCase{}, err
	}
	if err := s.audit.Log(ctx, q, caseID, domain.EventReportGenerated, &riskResult.Score); err != nil {
		return domain.ReadyCase{}, err
	}

	dossierJSON, _ := json.Marshal(dossier)
	evidenceJSON, _ := json.Marshal(bundle)
	explanationJSON, _ := json.Marshal(explanation)
	if err := q.UpdateCaseSnapshots(ctx, caseID, riskResult.Score, riskResult.Level,
		dossierJSON, evidenceJSON, explanationJSON); err != nil {
		return domain.ReadyCase{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return domain.ReadyCase{}, err
	}

	return domain.ReadyCase{
		CaseID:           caseID,
		AlertID:          alertID,
		ClientID:         dossier.ClientID,
		ClientName:       dossier.FullName,
		Risk:             riskResult,
		Evidence:         bundle,
		Explanation:      explanation,
		HumanExplanation: humanExplanation,
		ReportDraft:      reportDraft,
		Status:           domain.CaseStatusOpen,
		CreatedAt:        createdAt,
		RuleHitsCount:    len(ruleHits),
		EvidenceCount:    len(bundle.Items),
	}, nil
}

func (s *Service) persistRuleHits(ctx context.Context, q *repo.Queries, caseID int64, hits []domain.RuleResult) error {
	for _, h := range hits {
		evJSON, _ := json.Marshal(h.Evidence)
		if err := q.InsertRuleHit(ctx, caseID, h.Code, h.Name, h.Weight, evJSON, h.Explanation); err != nil {
			return err
		}
	}
	return nil
}

// mapExistingCase восстанавливает ReadyCase из персистентных снапшотов,
// ничего не пересчитывая и не вызывая LLM заново.
func (s *Service) mapExistingCase(ctx context.Context, q *repo.Queries, c domain.Case) (domain.ReadyCase, error) {
	reportDraft := ""
	if r, err := q.GetReportByCase(ctx, c.ID); err == nil {
		if r.FinalText != nil && *r.FinalText != "" {
			reportDraft = *r.FinalText
		} else {
			reportDraft = r.DraftText
		}
	}

	humanExplanation := ""
	if a, err := q.FirstAuditByEvent(ctx, c.ID, domain.EventLLMCalled); err == nil && a.LLMResponse != nil {
		humanExplanation = *a.LLMResponse
	}

	var bundle domain.EvidenceBundle
	var explanation domain.Explanation
	if err := json.Unmarshal(c.EvidenceJSON, &bundle); err != nil {
		return domain.ReadyCase{}, fmt.Errorf("restore evidence snapshot for case %d: %w", c.ID, err)
	}
	if err := json.Unmarshal(c.ExplanationJSON, &explanation); err != nil {
		return domain.ReadyCase{}, fmt.Errorf("restore explanation snapshot for case %d: %w", c.ID, err)
	}

	ruleHitsCount := 0
	for _, e := range bundle.Items {
		if e.Type == domain.EvidenceRuleHit {
			ruleHitsCount++
		}
	}

	clientName := ""
	if c.ClientID != nil {
		if cl, err := q.GetClient(ctx, *c.ClientID); err == nil {
			clientName = cl.FullName
		}
	}

	var alertID int64
	if c.AlertID != nil {
		alertID = *c.AlertID
	}
	var clientID int64
	if c.ClientID != nil {
		clientID = *c.ClientID
	}

	return domain.ReadyCase{
		CaseID:           c.ID,
		AlertID:          alertID,
		ClientID:         clientID,
		ClientName:       clientName,
		Risk:             domain.RiskResult{Score: c.RiskScore, Level: c.RiskLevel},
		Evidence:         bundle,
		Explanation:      explanation,
		HumanExplanation: humanExplanation,
		ReportDraft:      reportDraft,
		Status:           c.Status,
		CreatedAt:        c.CreatedAt,
		RuleHitsCount:    ruleHitsCount,
		EvidenceCount:    len(bundle.Items),
	}, nil
}
