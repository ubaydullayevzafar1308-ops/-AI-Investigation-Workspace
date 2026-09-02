package llm

import (
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

// SafeCaseJSON — безопасный срез кейса, ЕДИНСТВЕННОЕ, что видит LLM.
// Ни ФИО, ни ИНН, ни номеров счетов, ни сырых транзакций. Реальные
// субъекты заменены псевдонимами ("Клиент К-1", "Компания С-1").
// См. AI_LAYER_ARCHITECTURE.md §3.
type SafeCaseJSON struct {
	RiskScore int               `json:"riskScore"`
	RiskLevel string            `json:"riskLevel"`
	Client    SafeClientProfile `json:"client"`
	Reasons   []SafeReason      `json:"reasons"`
	Evidence  []SafeEvidence    `json:"evidence"`
}

type SafeClientProfile struct {
	Pseudonym         string `json:"pseudonym"`
	ClientType        string `json:"clientType"`
	YearsWithBank     int    `json:"yearsWithBank"`
	AccountsCount     int    `json:"accountsCount"`
	HasPreviousAlerts bool   `json:"hasPreviousAlerts"`
}

type SafeReason struct {
	Factor       string `json:"factor"`
	Contribution int    `json:"contribution"`
	Detail       string `json:"detail"`
}

type SafeEvidence struct {
	Type       string         `json:"type"`
	Title      string         `json:"title"`
	Aggregates map[string]any `json:"aggregates"`
}

// BuildSafeJSON строит SafeCaseJSON. САМАЯ критичная по безопасности
// часть системы — ошибка здесь означает утечку ФИО/ИНН в сторонний API.
func BuildSafeJSON(risk domain.RiskResult, explanation domain.Explanation, bundle domain.EvidenceBundle, d domain.Dossier) SafeCaseJSON {
	reg := buildRegistry(d)

	profile := SafeClientProfile{
		Pseudonym:         reg.clientPseudonym(),
		ClientType:        d.ClientType,
		YearsWithBank:     yearsWithBank(d),
		AccountsCount:     len(d.AccountIDs),
		HasPreviousAlerts: len(d.PastAlerts) > 0,
	}

	reasons := make([]SafeReason, 0, len(explanation.Reasons))
	for _, r := range explanation.Reasons {
		reasons = append(reasons, SafeReason{
			Factor:       r.Factor,
			Contribution: r.Contribution,
			Detail:       reg.pseudonymize(r.Detail),
		})
	}

	evidence := make([]SafeEvidence, 0, len(bundle.Items))
	for _, e := range bundle.Items {
		evidence = append(evidence, SafeEvidence{
			Type:       e.Type,
			Title:      reg.pseudonymize(e.Title),
			Aggregates: reg.pseudonymizeAggregates(e.Details),
		})
	}

	return SafeCaseJSON{
		RiskScore: risk.Score,
		RiskLevel: risk.Level,
		Client:    profile,
		Reasons:   reasons,
		Evidence:  evidence,
	}
}

func yearsWithBank(d domain.Dossier) int {
	if d.RegistrationDate == nil {
		return 0
	}
	now := time.Now()
	years := now.Year() - d.RegistrationDate.Year()
	anniv := d.RegistrationDate.AddDate(years, 0, 0)
	if anniv.After(now) {
		years--
	}
	if years < 0 {
		years = 0
	}
	return years
}

// ── реестр псевдонимов ──────────────────────────────────────────────

type pseudonymRegistry struct {
	keys           []string // порядок регистрации
	realToPseudo   map[string]string
	clientCounter  int
	companyCounter int
	clientPseudo   string
}

func newRegistry() *pseudonymRegistry {
	return &pseudonymRegistry{realToPseudo: map[string]string{}}
}

func (r *pseudonymRegistry) registerClient(name string) {
	if name == "" {
		return
	}
	if _, ok := r.realToPseudo[name]; ok {
		return
	}
	r.clientCounter++
	p := "Клиент К-" + strconv.Itoa(r.clientCounter)
	r.keys = append(r.keys, name)
	r.realToPseudo[name] = p
	if r.clientPseudo == "" {
		r.clientPseudo = p
	}
}

func (r *pseudonymRegistry) registerCompany(name string) {
	if name == "" {
		return
	}
	if _, ok := r.realToPseudo[name]; ok {
		return
	}
	r.companyCounter++
	r.keys = append(r.keys, name)
	r.realToPseudo[name] = "Компания С-" + strconv.Itoa(r.companyCounter)
}

func (r *pseudonymRegistry) clientPseudonym() string {
	if r.clientPseudo != "" {
		return r.clientPseudo
	}
	return "Клиент К-1"
}

// pseudonymize заменяет ВСЕ известные реальные имена на псевдонимы.
// КРИТИЧНО: сортируем по убыванию длины имени — иначе короткое имя,
// являющееся подстрокой длинного, заменится первым и оставит кусок ФИО.
func (r *pseudonymRegistry) pseudonymize(text string) string {
	if text == "" {
		return text
	}
	byLen := append([]string(nil), r.keys...)
	sort.SliceStable(byLen, func(i, j int) bool { return len(byLen[i]) > len(byLen[j]) })
	res := text
	for _, k := range byLen {
		res = strings.ReplaceAll(res, k, r.realToPseudo[k])
	}
	return res
}

func (r *pseudonymRegistry) pseudonymizeAggregates(details map[string]any) map[string]any {
	if len(details) == 0 {
		return map[string]any{}
	}
	out := make(map[string]any, len(details))
	for k, v := range details {
		switch val := v.(type) {
		case string:
			out[k] = r.pseudonymize(val)
		case []string:
			ns := make([]any, len(val))
			for i, s := range val {
				ns[i] = r.pseudonymize(s)
			}
			out[k] = ns
		case []any:
			ns := make([]any, len(val))
			for i, item := range val {
				if s, ok := item.(string); ok {
					ns[i] = r.pseudonymize(s)
				} else {
					ns[i] = item
				}
			}
			out[k] = ns
		default:
			out[k] = v
		}
	}
	return out
}

func buildRegistry(d domain.Dossier) *pseudonymRegistry {
	reg := newRegistry()
	reg.registerClient(d.FullName)

	for _, rel := range d.Relations {
		if rel.CounterpartType == "client" {
			reg.registerClient(rel.CounterpartLabel)
		} else {
			reg.registerCompany(rel.CounterpartLabel)
		}
	}
	for _, c := range d.RelatedCompanies {
		reg.registerCompany(c.Name)
	}
	for _, cycle := range d.MoneyCycles {
		for _, label := range cycle.PathLabels {
			if label != d.FullName {
				reg.registerCompany(label)
			}
		}
	}
	return reg
}
