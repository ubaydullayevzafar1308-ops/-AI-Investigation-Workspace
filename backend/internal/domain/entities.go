// Package domain содержит доменные структуры (таблицы БД) и DTO
// пайплайна Case Builder. Перенесено из uz.caseintel.entity / .dto
// Spring-версии.
package domain

import (
	"time"

	"github.com/shopspring/decimal"
)

// Константы значений полей — держим строками (как и в Java-версии),
// чтобы не заводить конвертеры enum'ов на уровне БД.
const (
	OwnerClient  = "client"
	OwnerCompany = "company"

	TxTypeTransfer      = "transfer"
	TxTypeCashIn        = "cash_in"
	TxTypeCashOut       = "cash_out"
	TxTypeCardPayment   = "card_payment"
	TxTypeInternational = "international"

	RelationFounder              = "founder"
	RelationDirector             = "director"
	RelationSameAddress          = "same_address"
	RelationSamePhone            = "same_phone"
	RelationSameDevice           = "same_device"
	RelationFrequentCounterparty = "frequent_counterparty"
	RelationFamily               = "family"

	AlertSeverityLow    = "low"
	AlertSeverityMedium = "medium"
	AlertSeverityHigh   = "high"

	AlertStatusNew           = "new"
	AlertStatusInvestigating = "investigating"
	AlertStatusClosed        = "closed"

	CaseStatusOpen      = "open"
	CaseStatusApproved  = "approved"
	CaseStatusRejected  = "rejected"
	CaseStatusEscalated = "escalated"

	RiskLow    = "low"
	RiskMedium = "medium"
	RiskHigh   = "high"

	EventCaseCreated       = "case_created"
	EventRulesExecuted     = "rules_executed"
	EventGraphBuilt        = "graph_built"
	EventEvidenceCollected = "evidence_collected"
	EventRiskScored        = "risk_scored"
	EventExplained         = "explained"
	EventLLMCalled         = "llm_called"
	EventReportGenerated   = "report_generated"
	EventDecisionMade      = "decision_made"

	ActorSystem = "system"

	ModuleAML        = "AML"
	ModuleFraud      = "FRAUD"
	ModuleCredit     = "CREDIT"
	ModuleKYC        = "KYC"
	ModuleCompliance = "COMPLIANCE"
)

type Client struct {
	ID               int64
	FullName         string
	BirthDate        *time.Time
	INN              *string
	Phone            *string
	DeviceID         *string
	Address          *string
	RegistrationDate time.Time
	ClientType       string
	RiskLevel        string
	Blacklisted      bool
	CreatedAt        time.Time
}

type Company struct {
	ID               int64
	Name             string
	INN              *string
	Address          *string
	RegistrationDate time.Time
	DirectorID       *int64
	Status           string
	Blacklisted      bool
	CreatedAt        time.Time
}

type Account struct {
	ID            int64
	OwnerType     string
	OwnerID       int64
	AccountNumber string
	Currency      string
	OpenedAt      time.Time
	Status        string
	CreatedAt     time.Time
}

type Transaction struct {
	ID            int64
	FromAccountID *int64
	ToAccountID   *int64
	Amount        decimal.Decimal
	Currency      string
	TxType        string
	Description   *string
	TxTimestamp   time.Time
	CreatedAt     time.Time
}

type Relationship struct {
	ID           int64
	SourceType   string
	SourceID     int64
	TargetType   string
	TargetID     int64
	RelationType string
	Confidence   decimal.Decimal
	CreatedAt    time.Time
}

type Alert struct {
	ID            int64
	TransactionID *int64
	ClientID      *int64
	TriggerReason string
	Severity      string
	Status        string
	CreatedAt     time.Time
}

// Case хранит JSON-снапшоты как сырые строки (JSONB-колонки) — они
// пишутся один раз в конце пайплайна и читаются фронтом одним запросом.
type Case struct {
	ID              int64
	AlertID         *int64
	ClientID        *int64
	RiskScore       int
	RiskLevel       string
	Status          string
	AnalystDecision *string
	DossierJSON     []byte
	EvidenceJSON    []byte
	ExplanationJSON []byte
	CreatedAt       time.Time
	ClosedAt        *time.Time
}

type RuleHit struct {
	ID           int64
	CaseID       int64
	RuleCode     string
	RuleName     string
	Weight       int
	EvidenceJSON []byte
	Explanation  string
	CreatedAt    time.Time
}

type Report struct {
	ID          int64     `json:"id"`
	CaseID      int64     `json:"caseId"`
	DraftText   string    `json:"draftText"`
	FinalText   *string   `json:"finalText"`
	GeneratedAt time.Time `json:"generatedAt"`
	ApprovedBy  *string   `json:"approvedBy"`
}

type AuditLog struct {
	ID           int64
	CaseID       *int64
	EventType    string
	RulesVersion *string
	RiskScore    *int
	EvidenceJSON []byte
	LLMProvider  *string
	LLMModel     *string
	LLMPrompt    *string
	LLMResponse  *string
	Actor        *string
	CreatedAt    time.Time
}

type FeatureFlag struct {
	ID          int64   `json:"id"`
	ModuleCode  string  `json:"moduleCode"`
	ModuleName  string  `json:"moduleName"`
	Enabled     bool    `json:"enabled"`
	Description *string `json:"description"`
}
