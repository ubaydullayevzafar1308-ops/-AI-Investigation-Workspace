package llm_test

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/evidence"
	"github.com/tiredjon/cbu/backend/internal/explain"
	"github.com/tiredjon/cbu/backend/internal/llm"
	"github.com/tiredjon/cbu/backend/internal/risk"
	"github.com/tiredjon/cbu/backend/internal/rules"
)

// goldenDossier примерно воспроизводит GoldenCaseScheme: дробление (R01),
// круговая схема (R03), общее устройство с blacklisted (R07), прошлый
// алерт (repeat offender +10).
func goldenDossier() domain.Dossier {
	base := time.Date(2026, 1, 1, 9, 0, 0, 0, time.UTC)
	dec := decimal.RequireFromString
	reg := base.AddDate(-3, 0, 0)
	return domain.Dossier{
		ClientID: 1, FullName: "Karimov Alisher Botirovich", ClientType: "individual",
		RegistrationDate: &reg,
		AccountIDs:       []int64{10},
		Transactions: []domain.TxView{
			{TransactionID: 1, Direction: domain.DirectionOut, Amount: dec("96500000"), TxType: domain.TxTypeTransfer, Timestamp: base},
			{TransactionID: 2, Direction: domain.DirectionOut, Amount: dec("98200000"), TxType: domain.TxTypeTransfer, Timestamp: base.Add(14 * time.Hour)},
			{TransactionID: 3, Direction: domain.DirectionOut, Amount: dec("95800000"), TxType: domain.TxTypeTransfer, Timestamp: base.Add(28 * time.Hour)},
		},
		Relations: []domain.RelationView{
			{CounterpartType: "client", CounterpartID: 99, CounterpartLabel: "Xolmatov Jasur Anvarovich",
				RelationType: domain.RelationSameDevice, CounterpartBlacklisted: true},
		},
		MoneyCycles: []domain.CycleView{
			{PathLabels: []string{"Karimov Alisher Botirovich", "OOO Barakat Trade", "OOO Vega Import", "Karimov Alisher Botirovich"},
				TotalAmount: dec("1175000000"), TransactionCount: 3},
		},
		PastAlerts: []domain.PastAlertView{
			{AlertID: 482, CreatedAt: base.AddDate(0, -4, 0)},
		},
	}
}

func TestGoldenPipeline_HighRisk(t *testing.T) {
	d := goldenDossier()
	hits := rules.NewEngine().RunAll(d)

	codes := map[string]bool{}
	for _, h := range hits {
		codes[h.Code] = true
	}
	for _, want := range []string{"R01", "R03", "R07"} {
		if !codes[want] {
			t.Errorf("expected rule %s to fire; fired: %v", want, codes)
		}
	}

	bundle := evidence.Collect(d.ClientID, hits, d)
	r := risk.Score(bundle)
	if r.Level != domain.RiskHigh {
		t.Fatalf("risk level = %s (score %d), want high", r.Level, r.Score)
	}
	// R01(20) + R03(25) + R07(20) + repeat offender(10) = 75
	if r.Score < 60 {
		t.Errorf("score = %d, want >= 60", r.Score)
	}

	exp := explain.Explain(r, bundle)
	if len(exp.Reasons) == 0 {
		t.Fatal("explanation has no reasons")
	}
	// reasons отсортированы по убыванию вклада
	for i := 1; i < len(exp.Reasons); i++ {
		if exp.Reasons[i-1].Contribution < exp.Reasons[i].Contribution {
			t.Errorf("reasons not sorted desc: %+v", exp.Reasons)
		}
	}
}

func TestSafeJSON_NoRealNamesLeak(t *testing.T) {
	d := goldenDossier()
	hits := rules.NewEngine().RunAll(d)
	bundle := evidence.Collect(d.ClientID, hits, d)
	r := risk.Score(bundle)
	exp := explain.Explain(r, bundle)

	safe := llm.BuildSafeJSON(r, exp, bundle, d)
	raw, err := json.Marshal(safe)
	if err != nil {
		t.Fatal(err)
	}
	blob := string(raw)

	for _, real := range []string{
		"Karimov", "Alisher", "Botirovich",
		"Xolmatov", "Jasur", "Anvarovich",
		"Barakat Trade", "Vega Import",
	} {
		if strings.Contains(blob, real) {
			t.Errorf("SafeCaseJSON leaked real name %q:\n%s", real, blob)
		}
	}
	if !strings.Contains(blob, "Клиент К-1") {
		t.Errorf("SafeCaseJSON missing client pseudonym:\n%s", blob)
	}
}
