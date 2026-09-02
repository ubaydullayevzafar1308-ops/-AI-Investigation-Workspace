// Package report — Report Generator (⑨). Персистит черновик отчёта от
// LLM и правки аналитика. Генерации текста здесь нет.
package report

import (
	"context"
	"fmt"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/repo"
)

type Service struct{}

func New() *Service { return &Service{} }

func (s *Service) SaveDraft(ctx context.Context, q *repo.Queries, caseID int64, draft string) error {
	return q.InsertReportDraft(ctx, caseID, draft)
}

// SaveFinalEdit — правки аналитика поверх черновика (PUT /api/cases/{id}/report).
func (s *Service) SaveFinalEdit(ctx context.Context, q *repo.Queries, caseID int64, finalText, approvedBy string) (domain.Report, error) {
	if _, err := q.GetReportByCase(ctx, caseID); err != nil {
		return domain.Report{}, fmt.Errorf("no report found for case %d", caseID)
	}
	return q.UpdateReportFinal(ctx, caseID, finalText, approvedBy)
}
