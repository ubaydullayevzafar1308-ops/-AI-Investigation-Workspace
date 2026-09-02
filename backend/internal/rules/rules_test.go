package rules

import (
	"strings"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

func dec(s string) decimal.Decimal { return decimal.RequireFromString(s) }

func tx(id int64, dir string, amount string, ts time.Time) domain.TxView {
	return domain.TxView{TransactionID: id, Direction: dir, Amount: dec(amount), TxType: domain.TxTypeTransfer, Timestamp: ts}
}

func findByCode(rr []domain.RuleResult, code string) (domain.RuleResult, bool) {
	for _, r := range rr {
		if r.Code == code {
			return r, true
		}
	}
	return domain.RuleResult{}, false
}

func TestR01_Structuring(t *testing.T) {
	base := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	d := domain.Dossier{Transactions: []domain.TxView{
		tx(1, domain.DirectionOut, "96000000", base),
		tx(2, domain.DirectionOut, "97500000", base.Add(12*time.Hour)),
		tx(3, domain.DirectionOut, "95200000", base.Add(24*time.Hour)),
		tx(4, domain.DirectionOut, "5000000", base), // ниже диапазона — не кандидат
	}}
	got := NewEngine().RunAll(d)
	res, ok := findByCode(got, "R01")
	if !ok {
		t.Fatal("R01 should fire")
	}
	if res.Weight != 20 {
		t.Errorf("weight = %d, want 20", res.Weight)
	}
	if !strings.Contains(res.Explanation, "дробления") {
		t.Errorf("explanation missing keyword: %q", res.Explanation)
	}
}

func TestR01_DoesNotFireBelowMinCount(t *testing.T) {
	base := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	d := domain.Dossier{Transactions: []domain.TxView{
		tx(1, domain.DirectionOut, "96000000", base),
		tx(2, domain.DirectionOut, "97500000", base.Add(12*time.Hour)),
	}}
	if _, ok := findByCode(NewEngine().RunAll(d), "R01"); ok {
		t.Error("R01 must not fire with only 2 candidates")
	}
}

func TestR03_CircularFlow(t *testing.T) {
	d := domain.Dossier{MoneyCycles: []domain.CycleView{
		{PathLabels: []string{"К", "A", "B", "К"}, TotalAmount: dec("875000000"), TransactionCount: 3},
		{PathLabels: []string{"К", "C", "К"}, TotalAmount: dec("50"), TransactionCount: 2}, // мелкий — игнор
	}}
	res, ok := findByCode(NewEngine().RunAll(d), "R03")
	if !ok {
		t.Fatal("R03 should fire")
	}
	if res.Weight != 25 {
		t.Errorf("weight = %d, want 25", res.Weight)
	}
	if res.Evidence["cycles_found"].(int) != 1 {
		t.Errorf("cycles_found = %v, want 1", res.Evidence["cycles_found"])
	}
}

func TestR07_SharedAttributes(t *testing.T) {
	d := domain.Dossier{Relations: []domain.RelationView{
		{CounterpartType: "client", CounterpartID: 77, CounterpartLabel: "Иванов",
			RelationType: domain.RelationSameDevice, CounterpartBlacklisted: true},
	}}
	res, ok := findByCode(NewEngine().RunAll(d), "R07")
	if !ok {
		t.Fatal("R07 should fire")
	}
	if !strings.Contains(res.Explanation, "общее устройство") {
		t.Errorf("explanation: %q", res.Explanation)
	}
}

func TestR10_HighRiskCounterparty(t *testing.T) {
	d := domain.Dossier{Relations: []domain.RelationView{
		{CounterpartType: "company", CounterpartID: 5, CounterpartLabel: "OOO X",
			RelationType: domain.RelationFrequentCounterparty, CounterpartBlacklisted: true},
		// R07 не должен сработать на frequent_counterparty
	}}
	got := NewEngine().RunAll(d)
	if _, ok := findByCode(got, "R10"); !ok {
		t.Fatal("R10 should fire")
	}
	if _, ok := findByCode(got, "R07"); ok {
		t.Error("R07 must NOT fire on frequent_counterparty")
	}
}
