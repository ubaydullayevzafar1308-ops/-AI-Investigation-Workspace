// Package audit — Audit Log (ARCHITECTURE.md §12). Каждый шаг пайплайна
// пишет сюда одну строку; вызовы LLM сохраняются целиком (prompt +
// response) для банковского аудита и проверки инварианта "LLM не видел
// реальных ФИО/ИНН".
package audit

import (
	"context"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/repo"
	"github.com/tiredjon/cbu/backend/internal/rules"
)

type Service struct{}

func New() *Service { return &Service{} }

func strptr(s string) *string { return &s }

// Log — обычное событие пайплайна (case_created, rules_executed, ...).
func (s *Service) Log(ctx context.Context, q *repo.Queries, caseID int64, event string, riskScore *int) error {
	cid := caseID
	return q.InsertAudit(ctx, domain.AuditLog{
		CaseID:       &cid,
		EventType:    event,
		RulesVersion: strptr(rules.RulesVersion),
		RiskScore:    riskScore,
		Actor:        strptr(domain.ActorSystem),
	})
}

// LogLLM — вызов LLM: полный prompt и полный response.
func (s *Service) LogLLM(ctx context.Context, q *repo.Queries, caseID int64, provider, model, fullPrompt, fullResponse string) error {
	cid := caseID
	return q.InsertAudit(ctx, domain.AuditLog{
		CaseID:       &cid,
		EventType:    domain.EventLLMCalled,
		RulesVersion: strptr(rules.RulesVersion),
		LLMProvider:  strptr(provider),
		LLMModel:     strptr(model),
		LLMPrompt:    strptr(fullPrompt),
		LLMResponse:  strptr(fullResponse),
		Actor:        strptr(domain.ActorSystem),
	})
}

// LogDecision — финальное решение аналитика (единственная запись, где
// actor не "system").
func (s *Service) LogDecision(ctx context.Context, q *repo.Queries, caseID int64, analystName string) error {
	cid := caseID
	return q.InsertAudit(ctx, domain.AuditLog{
		CaseID:       &cid,
		EventType:    domain.EventDecisionMade,
		RulesVersion: strptr(rules.RulesVersion),
		Actor:        strptr(analystName),
	})
}
