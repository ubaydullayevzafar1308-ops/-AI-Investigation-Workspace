// Package rules — Rule Engine (② в пайплайне). 10 детерминированных
// AML-правил R01..R10 (ARCHITECTURE.md §6). Каждое правило — чистая
// функция domain.Dossier -> (RuleResult, bool), без доступа к БД.
package rules

import (
	"sort"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

// RulesVersion пишется в audit_log.rules_version при каждом прогоне.
// Бампать вручную при изменении состава/весов правил.
const RulesVersion = "v1.0"

// Rule — контракт одного правила.
type Rule interface {
	Code() string
	Name() string
	Weight() int
	// Check возвращает (result, true) если правило сработало.
	Check(d domain.Dossier) (domain.RuleResult, bool)
}

// Engine прогоняет досье через все зарегистрированные правила.
type Engine struct {
	rules []Rule
}

func NewEngine() *Engine {
	return &Engine{rules: []Rule{
		r01{}, r02{}, r03{}, r04{}, r05{}, r06{}, r07{}, r08{}, r09{}, r10{},
	}}
}

func (e *Engine) RunAll(d domain.Dossier) []domain.RuleResult {
	var out []domain.RuleResult
	for _, r := range e.rules {
		if res, ok := r.Check(d); ok {
			out = append(out, res)
		}
	}
	return out
}

// ── общие помощники ─────────────────────────────────────────────────

func fmtAmount(d domain.Decimal) string { return domain.FormatAmount(d) }

// sortedByTime возвращает копию транзакций, отсортированную по времени.
func sortedByTime(txs []domain.TxView) []domain.TxView {
	out := append([]domain.TxView(nil), txs...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Timestamp.Before(out[j].Timestamp) })
	return out
}

func txIDs(txs []domain.TxView) []int64 {
	out := make([]int64, len(txs))
	for i, t := range txs {
		out[i] = t.TransactionID
	}
	return out
}

func sumAmounts(txs []domain.TxView) domain.Decimal {
	total := domain.Decimal{}
	for _, t := range txs {
		total = total.Add(t.Amount)
	}
	return total
}
