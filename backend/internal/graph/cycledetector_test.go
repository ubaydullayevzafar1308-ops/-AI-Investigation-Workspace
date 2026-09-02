package graph

import (
	"testing"

	"github.com/shopspring/decimal"
)

func d(s string) decimal.Decimal { return decimal.RequireFromString(s) }

func TestCycleDetector_FindsSimpleCycle(t *testing.T) {
	edges := []MoneyEdge{
		{FromNodeID: "client_1", ToNodeID: "company_1", Amount: d("300000000"), Count: 1},
		{FromNodeID: "company_1", ToNodeID: "company_2", Amount: d("290000000"), Count: 1},
		{FromNodeID: "company_2", ToNodeID: "client_1", Amount: d("285000000"), Count: 1},
	}
	labels := map[string]string{
		"client_1": "Клиент К", "company_1": "OOO A", "company_2": "OOO B",
	}
	det := NewCycleDetector(edges, labels)
	cycles := det.FindCyclesFrom("client_1")

	if len(cycles) != 1 {
		t.Fatalf("want 1 cycle, got %d", len(cycles))
	}
	c := cycles[0]
	if got := c.TotalAmount.String(); got != "875000000" {
		t.Errorf("total amount = %s, want 875000000", got)
	}
	if c.TransactionCount != 3 {
		t.Errorf("tx count = %d, want 3", c.TransactionCount)
	}
	// путь замкнут: 4 метки, первая == последняя
	if len(c.PathLabels) != 4 || c.PathLabels[0] != c.PathLabels[3] {
		t.Errorf("path labels not closed: %v", c.PathLabels)
	}
}

func TestCycleDetector_IgnoresTwoNodeBounce(t *testing.T) {
	edges := []MoneyEdge{
		{FromNodeID: "client_1", ToNodeID: "company_1", Amount: d("100"), Count: 1},
		{FromNodeID: "company_1", ToNodeID: "client_1", Amount: d("100"), Count: 1},
	}
	det := NewCycleDetector(edges, map[string]string{})
	if got := det.FindCyclesFrom("client_1"); len(got) != 0 {
		t.Errorf("want no cycles for 2-node bounce, got %d", len(got))
	}
}

func TestCycleDetector_CycleEdgesMarked(t *testing.T) {
	edges := []MoneyEdge{
		{FromNodeID: "client_1", ToNodeID: "company_1", Amount: d("10"), Count: 1},
		{FromNodeID: "company_1", ToNodeID: "company_2", Amount: d("10"), Count: 1},
		{FromNodeID: "company_2", ToNodeID: "client_1", Amount: d("10"), Count: 1},
		{FromNodeID: "client_1", ToNodeID: "company_9", Amount: d("10"), Count: 1}, // не в цикле
	}
	det := NewCycleDetector(edges, map[string]string{})
	marked := det.FindCycleEdgesFrom("client_1")
	if len(marked) != 3 {
		t.Fatalf("want 3 cycle edges, got %d", len(marked))
	}
	if marked[3] {
		t.Errorf("edge 3 (client_1->company_9) must not be marked")
	}
}
