// Package risk — Risk Engine (⑤). Считает Risk Score ТОЛЬКО из
// EvidenceBundle: суммирует веса rule_hit и применяет усилитель
// "повторный фигурант". Детерминированно. См. ARCHITECTURE.md §9.
package risk

import "github.com/tiredjon/cbu/backend/internal/domain"

const (
	RepeatOffenderBonus = 10
	HighThreshold       = 60
	MediumThreshold     = 30
)

func Score(bundle domain.EvidenceBundle) domain.RiskResult {
	raw := 0
	repeatOffender := false
	for _, e := range bundle.Items {
		raw += e.Weight
		if e.Type == domain.EvidencePreviousAlert {
			repeatOffender = true
		}
	}
	if repeatOffender {
		raw += RepeatOffenderBonus
	}

	score := raw
	if score > 100 {
		score = 100
	}

	level := domain.RiskLow
	switch {
	case score >= HighThreshold:
		level = domain.RiskHigh
	case score >= MediumThreshold:
		level = domain.RiskMedium
	}
	return domain.RiskResult{Score: score, Level: level}
}
