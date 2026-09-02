// Package explain — Explainability Engine (⑥). Раскладывает Risk Score
// на структурированный список причин (фактор -> вклад -> деталь).
// Не пересчитывает риск. См. ARCHITECTURE.md §10.
package explain

import (
	"fmt"
	"sort"
	"strings"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/risk"
)

func Explain(r domain.RiskResult, bundle domain.EvidenceBundle) domain.Explanation {
	var reasons []domain.Reason

	for _, e := range bundle.Items {
		if e.Type == domain.EvidenceRuleHit && e.Weight > 0 {
			reasons = append(reasons, domain.Reason{
				Factor:       ruleFactorLabel(e.Title),
				Contribution: e.Weight,
				Detail:       formatDetail(e),
			})
		}
	}

	repeatOffender := false
	for _, e := range bundle.Items {
		if e.Type == domain.EvidencePreviousAlert {
			repeatOffender = true
			break
		}
	}
	if repeatOffender {
		reasons = append(reasons, domain.Reason{
			Factor:       "Повторный фигурант",
			Contribution: risk.RepeatOffenderBonus,
			Detail:       "клиент уже фигурировал в прошлых алертах",
		})
	}

	sort.SliceStable(reasons, func(i, j int) bool {
		return reasons[i].Contribution > reasons[j].Contribution
	})
	reasons = applyCapAnnotation(reasons, r.Score)

	return domain.Explanation{RiskScore: r.Score, RiskLevel: r.Level, Reasons: reasons}
}

// "Rule R01: Structuring (дробление сумм)" -> "Structuring (дробление сумм)".
func ruleFactorLabel(title string) string {
	if i := strings.Index(title, ": "); i >= 0 {
		return title[i+2:]
	}
	return title
}

func formatDetail(e domain.Evidence) string {
	if len(e.Details) == 0 {
		return ""
	}
	keys := make([]string, 0, len(e.Details))
	for k := range e.Details {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s: %v", k, e.Details[k]))
	}
	return strings.Join(parts, ", ")
}

// applyCapAnnotation: если сумма вкладов превышает капнутый score,
// последняя (наименее весомая) причина помечается как частично учтённая.
func applyCapAnnotation(reasons []domain.Reason, cappedScore int) []domain.Reason {
	if len(reasons) == 0 {
		return reasons
	}
	rawSum := 0
	for _, r := range reasons {
		rawSum += r.Contribution
	}
	if rawSum <= cappedScore {
		return reasons
	}
	last := reasons[len(reasons)-1]
	if last.Detail == "" {
		last.Detail = fmt.Sprintf("капнуто до %d", cappedScore)
	} else {
		last.Detail = fmt.Sprintf("%s (капнуто до %d)", last.Detail, cappedScore)
	}
	out := append([]domain.Reason(nil), reasons[:len(reasons)-1]...)
	out = append(out, last)
	return out
}
