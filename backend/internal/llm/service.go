// Package llm — фасад LLM-слоя: 3 функции + кэш + audit + fallback.
// Роль LLM — только язык, не анализ. Ни один метод не передаёт LLM
// ничего, кроме SafeCaseJSON. См. AI_LAYER_ARCHITECTURE.md §2, §9.
package llm

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/tiredjon/cbu/backend/internal/audit"
	"github.com/tiredjon/cbu/backend/internal/repo"
)

type Service struct {
	adapter Adapter
	cache   *ResponseCache
	audit   *audit.Service
}

func NewService(adapter Adapter, cache *ResponseCache, auditSvc *audit.Service) *Service {
	return &Service{adapter: adapter, cache: cache, audit: auditSvc}
}

// ExplainRisk — вызывается автоматически при сборке кейса.
func (s *Service) ExplainRisk(ctx context.Context, q *repo.Queries, caseID int64, safe SafeCaseJSON) string {
	userPrompt := fmt.Sprintf(explainRiskTemplate, mustJSON(safe))
	return s.callWithFallback(ctx, q, caseID, userPrompt,
		"Автоматическое объяснение временно недоступно. См. структурированные причины выше.")
}

// GenerateReport — вызывается автоматически при сборке кейса.
func (s *Service) GenerateReport(ctx context.Context, q *repo.Queries, caseID int64, safe SafeCaseJSON) string {
	userPrompt := fmt.Sprintf(generateReportTemplate, mustJSON(safe))
	return s.callWithFallback(ctx, q, caseID, userPrompt,
		"Черновик отчёта будет сгенерирован позже.")
}

// AnswerQuestion — по запросу из чата на фронте.
func (s *Service) AnswerQuestion(ctx context.Context, q *repo.Queries, caseID int64, safe SafeCaseJSON, question string) string {
	userPrompt := fmt.Sprintf(answerQuestionTemplate, mustJSON(safe), question)
	return s.callWithFallback(ctx, q, caseID, userPrompt,
		"Не удалось обработать вопрос — попробуйте ещё раз позже.")
}

// callWithFallback: кэш -> адаптер (один повтор) -> audit -> fallback при ошибке.
// Кейс собирается полноценно независимо от того, что вернул LLM.
func (s *Service) callWithFallback(ctx context.Context, q *repo.Queries, caseID int64, userPrompt, fallback string) string {
	provider := s.adapter.ProviderName()
	model := s.adapter.ModelName()

	if cached, ok := s.cache.Get(provider, model, systemPrompt, userPrompt); ok {
		return cached
	}

	fullPrompt := systemPrompt + "\n\n" + userPrompt

	response, err := s.completeWithOneRetry(ctx, userPrompt)
	if err != nil {
		_ = s.audit.LogLLM(ctx, q, caseID, provider, model, fullPrompt, "[ERROR] "+err.Error())
		return fallback
	}

	_ = s.audit.LogLLM(ctx, q, caseID, provider, model, fullPrompt, response)
	s.cache.Put(provider, model, systemPrompt, userPrompt, response)
	return response
}

func (s *Service) completeWithOneRetry(ctx context.Context, userPrompt string) (string, error) {
	resp, err := s.adapter.Complete(ctx, systemPrompt, userPrompt)
	if err == nil {
		return resp, nil
	}
	return s.adapter.Complete(ctx, systemPrompt, userPrompt)
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}
