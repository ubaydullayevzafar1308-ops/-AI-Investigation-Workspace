// Package evidence — Evidence Collector (④ в пайплайне). Агрегирует все
// доказательства в единый пакет перед оценкой риска. Чистая функция над
// готовыми данными Rule Engine и Data Collector — сам в БД не ходит.
package evidence

import (
	"fmt"
	"strings"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

const (
	nightStartHour = 0
	nightEndHour   = 5
)

// Collect собирает EvidenceBundle. Порядок items важен для читаемости
// пакета аналитиком: правила -> связи -> циклы -> компании -> история ->
// аномалии.
func Collect(clientID int64, ruleResults []domain.RuleResult, d domain.Dossier) domain.EvidenceBundle {
	var items []domain.Evidence

	items = append(items, fromRuleHits(ruleResults)...)
	items = append(items, fromRelations(d.Relations)...)
	items = append(items, fromMoneyCycles(d.MoneyCycles)...)
	items = append(items, fromRelatedCompanies(d.RelatedCompanies)...)
	items = append(items, fromPastAlerts(d.PastAlerts)...)
	items = append(items, fromNightTransactions(d)...)

	total := 0
	for _, e := range items {
		if e.Type == domain.EvidenceRuleHit {
			total += e.Weight
		}
	}
	return domain.EvidenceBundle{ClientID: clientID, Items: items, TotalRuleWeight: total}
}

func fromRuleHits(rr []domain.RuleResult) []domain.Evidence {
	out := make([]domain.Evidence, 0, len(rr))
	for _, r := range rr {
		out = append(out, domain.Evidence{
			Type:    domain.EvidenceRuleHit,
			Title:   fmt.Sprintf("Rule %s: %s", r.Code, r.Name),
			Weight:  r.Weight,
			Details: r.Evidence,
		})
	}
	return out
}

func fromRelations(relations []domain.RelationView) []domain.Evidence {
	var out []domain.Evidence
	for _, r := range relations {
		if !r.CounterpartBlacklisted {
			continue
		}
		sameDevice := r.RelationType == domain.RelationSameDevice
		var title, typ string
		if sameDevice {
			title = fmt.Sprintf("Same device: %s (blacklisted)", r.CounterpartLabel)
			typ = domain.EvidenceSharedDevice
		} else {
			title = fmt.Sprintf("Related %s: %s (%s)", r.CounterpartType, r.CounterpartLabel, domain.RelationLabelRU(r.RelationType))
			typ = domain.EvidenceRelation
		}
		out = append(out, domain.Evidence{
			Type:   typ,
			Title:  title,
			Weight: 0,
			Details: map[string]any{
				"counterpart_id":   r.CounterpartID,
				"counterpart_type": r.CounterpartType,
				"relation_type":    r.RelationType,
			},
		})
	}
	return out
}

func fromMoneyCycles(cycles []domain.CycleView) []domain.Evidence {
	var out []domain.Evidence
	for _, c := range cycles {
		out = append(out, domain.Evidence{
			Type:   domain.EvidenceSuspiciousTx,
			Title:  "Circular transfers: " + strings.Join(c.PathLabels, " → "),
			Weight: 0,
			Details: map[string]any{
				"total_amount":      c.TotalAmount,
				"transaction_count": c.TransactionCount,
			},
		})
	}
	return out
}

func fromRelatedCompanies(companies []domain.CompanyView) []domain.Evidence {
	var out []domain.Evidence
	for _, c := range companies {
		if !c.Blacklisted {
			continue
		}
		out = append(out, domain.Evidence{
			Type:    domain.EvidenceRelation,
			Title:   fmt.Sprintf("Related company: %s (blacklisted, %s)", c.Name, c.RoleOfClient),
			Weight:  0,
			Details: map[string]any{"company_id": c.CompanyID, "role": c.RoleOfClient},
		})
	}
	return out
}

func fromPastAlerts(pastAlerts []domain.PastAlertView) []domain.Evidence {
	var out []domain.Evidence
	for _, a := range pastAlerts {
		statusLabel := "не расследовался"
		if a.Status != nil {
			statusLabel = *a.Status
		}
		var caseID any = "none"
		if a.CaseID != nil {
			caseID = *a.CaseID
		}
		out = append(out, domain.Evidence{
			Type:   domain.EvidencePreviousAlert,
			Title:  fmt.Sprintf("Previous alert: #%d (%s)", a.AlertID, statusLabel),
			Weight: 0,
			Details: map[string]any{
				"alert_id": a.AlertID,
				"case_id":  caseID,
				"status":   statusLabel,
			},
		})
	}
	return out
}

func fromNightTransactions(d domain.Dossier) []domain.Evidence {
	night := 0
	for _, tx := range d.Transactions {
		h := tx.Timestamp.Hour()
		if h >= nightStartHour && h < nightEndHour {
			night++
		}
	}
	if night == 0 {
		return nil
	}
	return []domain.Evidence{{
		Type:    domain.EvidenceAnomaly,
		Title:   fmt.Sprintf("Night transactions: %d операций %02d:00–%02d:00", night, nightStartHour, nightEndHour),
		Weight:  0,
		Details: map[string]any{"count": night},
	}}
}
