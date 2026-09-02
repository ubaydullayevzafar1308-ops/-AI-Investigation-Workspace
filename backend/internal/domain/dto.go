package domain

import (
	"time"

	"github.com/shopspring/decimal"
)

// ── Data Collector (①) — досье клиента ─────────────────────────────────

const (
	DirectionIn  = "in"
	DirectionOut = "out"
)

// Dossier — единственный вход для Rule Engine (②). Каждое правило читает
// из него и ничего не запрашивает из БД напрямую.
type Dossier struct {
	ClientID         int64           `json:"clientId"`
	FullName         string          `json:"fullName"`
	INN              string          `json:"inn"`
	Phone            string          `json:"phone"`
	DeviceID         string          `json:"deviceId"`
	Address          string          `json:"address"`
	RegistrationDate *time.Time      `json:"registrationDate"`
	ClientType       string          `json:"clientType"`
	Blacklisted      bool            `json:"blacklisted"`
	AccountIDs       []int64         `json:"accountIds"`
	Transactions     []TxView        `json:"transactions"`
	Relations        []RelationView  `json:"relations"`
	RelatedCompanies []CompanyView   `json:"relatedCompanies"`
	PastAlerts       []PastAlertView `json:"pastAlerts"`
	MoneyCycles      []CycleView     `json:"moneyCycles"`
}

type TxView struct {
	TransactionID int64           `json:"transactionId"`
	FromAccountID *int64          `json:"fromAccountId"`
	ToAccountID   *int64          `json:"toAccountId"`
	Direction     string          `json:"direction"` // in | out относительно счетов клиента
	Amount        decimal.Decimal `json:"amount"`
	Currency      string          `json:"currency"`
	TxType        string          `json:"txType"`
	Timestamp     time.Time       `json:"timestamp"`
}

type RelationView struct {
	CounterpartType        string `json:"counterpartType"` // client | company
	CounterpartID          int64  `json:"counterpartId"`
	CounterpartLabel       string `json:"counterpartLabel"`
	RelationType           string `json:"relationType"`
	CounterpartBlacklisted bool   `json:"counterpartBlacklisted"`
}

type CompanyView struct {
	CompanyID        int64           `json:"companyId"`
	Name             string          `json:"name"`
	RegistrationDate time.Time       `json:"registrationDate"`
	Blacklisted      bool            `json:"blacklisted"`
	RoleOfClient     string          `json:"roleOfClient"` // director | founder
	TurnoverAmount   decimal.Decimal `json:"turnoverAmount"`
}

type PastAlertView struct {
	AlertID   int64     `json:"alertId"`
	CaseID    *int64    `json:"caseId"`
	Status    *string   `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}

// CycleView — один найденный цикл денежного потока. pathLabels —
// замкнутый путь (последний элемент равен первому).
type CycleView struct {
	PathLabels       []string        `json:"pathLabels"`
	TotalAmount      decimal.Decimal `json:"totalAmount"`
	TransactionCount int             `json:"transactionCount"`
}

// ── Rule Engine (②) ───────────────────────────────────────────────────

type RuleResult struct {
	Code        string         `json:"code"`
	Name        string         `json:"name"`
	Weight      int            `json:"weight"`
	Evidence    map[string]any `json:"evidence"`
	Explanation string         `json:"explanation"`
}

// ── Evidence Collector (④) ────────────────────────────────────────────

const (
	EvidenceRuleHit       = "rule_hit"
	EvidenceRelation      = "relation"
	EvidenceSuspiciousTx  = "suspicious_tx"
	EvidencePreviousAlert = "previous_alert"
	EvidenceAnomaly       = "anomaly"
	EvidenceSharedDevice  = "shared_device"
)

type Evidence struct {
	Type    string         `json:"type"`
	Title   string         `json:"title"`
	Weight  int            `json:"weight"`
	Details map[string]any `json:"details"`
}

type EvidenceBundle struct {
	ClientID        int64      `json:"clientId"`
	Items           []Evidence `json:"items"`
	TotalRuleWeight int        `json:"totalRuleWeight"`
}

// ── Risk Engine (⑤) ──────────────────────────────────────────────────

type RiskResult struct {
	Score int    `json:"score"`
	Level string `json:"level"`
}

// ── Explainability Engine (⑥) ────────────────────────────────────────

type Reason struct {
	Factor       string `json:"factor"`
	Contribution int    `json:"contribution"`
	Detail       string `json:"detail"`
}

type Explanation struct {
	RiskScore int      `json:"riskScore"`
	RiskLevel string   `json:"riskLevel"`
	Reasons   []Reason `json:"reasons"`
}

// ── Graph Engine (③) ─────────────────────────────────────────────────

type GraphNode struct {
	ID      string `json:"id"`   // "client_123" | "company_45"
	Type    string `json:"type"` // client | company
	Label   string `json:"label"`
	Flagged bool   `json:"flagged"`
}

type GraphEdge struct {
	Source       string           `json:"source"`
	Target       string           `json:"target"`
	Kind         string           `json:"kind"` // money_flow | relation
	Total        *decimal.Decimal `json:"total"`
	Count        *int             `json:"count"`
	Suspicious   *bool            `json:"suspicious"`
	RelationType *string          `json:"relationType"`
}

type Graph struct {
	Nodes []GraphNode `json:"nodes"`
	Edges []GraphEdge `json:"edges"`
}

// ── Ready Case (выход Case Builder) ──────────────────────────────────

type ReadyCase struct {
	CaseID           int64          `json:"caseId"`
	AlertID          int64          `json:"alertId"`
	ClientID         int64          `json:"clientId"`
	ClientName       string         `json:"clientName"`
	Risk             RiskResult     `json:"risk"`
	Evidence         EvidenceBundle `json:"evidence"`
	Explanation      Explanation    `json:"explanation"`
	HumanExplanation string         `json:"humanExplanation"`
	ReportDraft      string         `json:"reportDraft"`
	Status           string         `json:"status"`
	CreatedAt        time.Time      `json:"createdAt"`
	RuleHitsCount    int            `json:"ruleHitsCount"`
	EvidenceCount    int            `json:"evidenceCount"`
}
