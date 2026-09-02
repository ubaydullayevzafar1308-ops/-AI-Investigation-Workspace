package rules

import (
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/shopspring/decimal"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

func relationLabel(t string) string { return domain.RelationLabelRU(t) }

func isCloseAmount(a, b, tolerance decimal.Decimal) bool {
	if b.IsZero() {
		return a.IsZero()
	}
	diff := a.Sub(b).Abs()
	tol := b.Abs().Mul(tolerance)
	return diff.Cmp(tol) <= 0
}

// ── R01 Structuring ─────────────────────────────────────────────────

type r01 struct{}

func (r01) Code() string { return "R01" }
func (r01) Name() string { return "Structuring (дробление сумм)" }
func (r01) Weight() int  { return 20 }

var (
	r01Threshold = decimal.RequireFromString("100000000")
	r01NearRatio = decimal.RequireFromString("0.90")
)

const (
	r01WindowDays = 3
	r01MinCount   = 3
)

func (r r01) Check(d domain.Dossier) (domain.RuleResult, bool) {
	lower := r01Threshold.Mul(r01NearRatio)

	var candidates []domain.TxView
	for _, tx := range d.Transactions {
		if tx.Amount.Cmp(lower) >= 0 && tx.Amount.Cmp(r01Threshold) < 0 {
			candidates = append(candidates, tx)
		}
	}
	candidates = sortedByTime(candidates)

	hit, ok := r01FindWindow(candidates)
	if !ok {
		return domain.RuleResult{}, false
	}
	total := sumAmounts(hit)
	expl := fmt.Sprintf(
		"Обнаружено %d операций на суммы %s–%s (чуть ниже порога обязательного контроля %s) за %d дня — признак умышленного дробления.",
		len(hit), fmtAmount(lower), fmtAmount(r01Threshold), fmtAmount(r01Threshold), r01WindowDays)

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"transaction_ids": txIDs(hit),
			"total_amount":    total,
			"count":           len(hit),
			"window_days":     r01WindowDays,
		},
		Explanation: expl,
	}, true
}

func r01FindWindow(sorted []domain.TxView) ([]domain.TxView, bool) {
	for start := 0; start < len(sorted); start++ {
		end := sorted[start].Timestamp.Add(r01WindowDays * 24 * time.Hour)
		var inWindow []domain.TxView
		for i := start; i < len(sorted); i++ {
			if sorted[i].Timestamp.After(end) {
				break
			}
			inWindow = append(inWindow, sorted[i])
		}
		if len(inWindow) >= r01MinCount {
			return inWindow, true
		}
	}
	return nil, false
}

// ── R02 Rapid movement ─────────────────────────────────────────────

type r02 struct{}

func (r02) Code() string { return "R02" }
func (r02) Name() string { return "Rapid movement (транзитные переводы)" }
func (r02) Weight() int  { return 15 }

var r02Tolerance = decimal.RequireFromString("0.10")

const r02MaxGap = 6 * time.Hour

func (r r02) Check(d domain.Dossier) (domain.RuleResult, bool) {
	sorted := sortedByTime(d.Transactions)

	for _, incoming := range sorted {
		if incoming.Direction != domain.DirectionIn {
			continue
		}
		gapEnd := incoming.Timestamp.Add(r02MaxGap)
		for _, out := range sorted {
			if out.Direction != domain.DirectionOut {
				continue
			}
			if out.Timestamp.Before(incoming.Timestamp) || out.Timestamp.After(gapEnd) {
				continue
			}
			if !isCloseAmount(out.Amount, incoming.Amount, r02Tolerance) {
				continue
			}
			gapMinutes := int64(out.Timestamp.Sub(incoming.Timestamp).Minutes())
			expl := fmt.Sprintf(
				"Поступление %s прошло транзитом: списание почти той же суммы (%s) через %d мин.",
				fmtAmount(incoming.Amount), fmtAmount(out.Amount), gapMinutes)
			return domain.RuleResult{
				Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
				Evidence: map[string]any{
					"incoming_transaction_id": incoming.TransactionID,
					"outgoing_transaction_id": out.TransactionID,
					"gap_minutes":             gapMinutes,
					"amount":                  incoming.Amount,
				},
				Explanation: expl,
			}, true
		}
	}
	return domain.RuleResult{}, false
}

// ── R03 Circular flow ──────────────────────────────────────────────

type r03 struct{}

func (r03) Code() string { return "R03" }
func (r03) Name() string { return "Circular flow (круговая схема переводов)" }
func (r03) Weight() int  { return 25 }

var r03MinTurnover = decimal.RequireFromString("100000000")

func (r r03) Check(d domain.Dossier) (domain.RuleResult, bool) {
	if len(d.MoneyCycles) == 0 {
		return domain.RuleResult{}, false
	}
	var significant []domain.CycleView
	for _, c := range d.MoneyCycles {
		if c.TotalAmount.Cmp(r03MinTurnover) > 0 {
			significant = append(significant, c)
		}
	}
	if len(significant) == 0 {
		return domain.RuleResult{}, false
	}
	biggest := significant[0]
	for _, c := range significant[1:] {
		if c.TotalAmount.Cmp(biggest.TotalAmount) > 0 {
			biggest = c
		}
	}
	path := strings.Join(biggest.PathLabels, " → ")
	expl := fmt.Sprintf(
		"Обнаружена круговая схема переводов: %s, общий оборот %s (%d операций).",
		path, fmtAmount(biggest.TotalAmount), biggest.TransactionCount)

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"path":              biggest.PathLabels,
			"total_amount":      biggest.TotalAmount,
			"transaction_count": biggest.TransactionCount,
			"cycles_found":      len(significant),
		},
		Explanation: expl,
	}, true
}

// ── R04 New entity spike ───────────────────────────────────────────

type r04 struct{}

func (r04) Code() string { return "R04" }
func (r04) Name() string {
	return "New entity spike (крупный оборот у новой компании)"
}
func (r04) Weight() int { return 15 }

const r04MaxAgeDays = 90

var r04TurnoverThreshold = decimal.RequireFromString("200000000")

func daysBetween(from, to time.Time) int64 {
	return int64(to.Sub(from).Hours()) / 24
}

func (r r04) Check(d domain.Dossier) (domain.RuleResult, bool) {
	today := time.Now().UTC().Truncate(24 * time.Hour)

	var hits []domain.CompanyView
	for _, c := range d.RelatedCompanies {
		if daysBetween(c.RegistrationDate, today) <= r04MaxAgeDays &&
			c.TurnoverAmount.Cmp(r04TurnoverThreshold) >= 0 {
			hits = append(hits, c)
		}
	}
	if len(hits) == 0 {
		return domain.RuleResult{}, false
	}
	worst := hits[0]
	for _, c := range hits[1:] {
		if c.TurnoverAmount.Cmp(worst.TurnoverAmount) > 0 {
			worst = c
		}
	}
	ageDays := daysBetween(worst.RegistrationDate, today)
	expl := fmt.Sprintf(
		"Компания «%s» зарегистрирована %d дн. назад, но оборот по её счетам уже составил %s — нетипично для новой организации.",
		worst.Name, ageDays, fmtAmount(worst.TurnoverAmount))

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"company_id":              worst.CompanyID,
			"age_days":                ageDays,
			"turnover_amount":         worst.TurnoverAmount,
			"flagged_companies_count": len(hits),
		},
		Explanation: expl,
	}, true
}

// ── R05 Dormant awakening ──────────────────────────────────────────

type r05 struct{}

func (r05) Code() string { return "R05" }
func (r05) Name() string { return "Dormant awakening (спящий счёт ожил)" }
func (r05) Weight() int  { return 10 }

const r05DormantPeriod = 180 * 24 * time.Hour

func (r r05) Check(d domain.Dossier) (domain.RuleResult, bool) {
	sorted := sortedByTime(d.Transactions)
	if len(sorted) < 2 {
		return domain.RuleResult{}, false
	}
	for i := 1; i < len(sorted); i++ {
		gap := sorted[i].Timestamp.Sub(sorted[i-1].Timestamp)
		if gap >= r05DormantPeriod {
			gapDays := int64(gap.Hours()) / 24
			expl := fmt.Sprintf(
				"Счёт не имел операций %d дней, затем внезапно появилась операция на %s.",
				gapDays, fmtAmount(sorted[i].Amount))
			return domain.RuleResult{
				Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
				Evidence: map[string]any{
					"dormant_days":                 gapDays,
					"awakening_transaction_id":     sorted[i].TransactionID,
					"last_activity_transaction_id": sorted[i-1].TransactionID,
					"amount":                       sorted[i].Amount,
				},
				Explanation: expl,
			}, true
		}
	}
	return domain.RuleResult{}, false
}

// ── R06 Cash intensive ─────────────────────────────────────────────

type r06 struct{}

func (r06) Code() string { return "R06" }
func (r06) Name() string { return "Cash intensive (высокая доля наличных)" }
func (r06) Weight() int  { return 10 }

var r06Threshold = decimal.RequireFromString("0.70")

const r06MinTxCount = 5

func (r r06) Check(d domain.Dossier) (domain.RuleResult, bool) {
	all := d.Transactions
	if len(all) < r06MinTxCount {
		return domain.RuleResult{}, false
	}
	total := sumAmounts(all)
	if total.IsZero() {
		return domain.RuleResult{}, false
	}
	cash := decimal.Zero
	for _, t := range all {
		if t.TxType == domain.TxTypeCashIn || t.TxType == domain.TxTypeCashOut {
			cash = cash.Add(t.Amount)
		}
	}
	share := cash.Div(total)
	if share.Cmp(r06Threshold) < 0 {
		return domain.RuleResult{}, false
	}
	sharePercent := int(share.Mul(decimal.NewFromInt(100)).IntPart())
	expl := fmt.Sprintf(
		"%d%% оборота клиента приходится на наличные операции (%s из %s) — нетипично высокая доля.",
		sharePercent, fmtAmount(cash), fmtAmount(total))

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"cash_share_percent": sharePercent,
			"cash_amount":        cash,
			"total_amount":       total,
		},
		Explanation: expl,
	}, true
}

// ── R07 Shared attributes ──────────────────────────────────────────

type r07 struct{}

func (r07) Code() string { return "R07" }
func (r07) Name() string {
	return "Shared attributes (общий признак с blacklisted-субъектом)"
}
func (r07) Weight() int { return 20 }

var r07RelevantTypes = map[string]bool{
	domain.RelationSameAddress: true,
	domain.RelationSamePhone:   true,
	domain.RelationSameDevice:  true,
	domain.RelationDirector:    true,
	domain.RelationFounder:     true,
	domain.RelationFamily:      true,
}

func (r r07) Check(d domain.Dossier) (domain.RuleResult, bool) {
	var hits []domain.RelationView
	for _, rel := range d.Relations {
		if rel.CounterpartBlacklisted && r07RelevantTypes[rel.RelationType] {
			hits = append(hits, rel)
		}
	}
	if len(hits) == 0 {
		return domain.RuleResult{}, false
	}
	first := hits[0]
	expl := fmt.Sprintf("Обнаружена связь «%s» с %s из чёрного списка.",
		relationLabel(first.RelationType), first.CounterpartLabel)

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"relation_type":    first.RelationType,
			"counterpart_id":   first.CounterpartID,
			"counterpart_type": first.CounterpartType,
			"matches_count":    len(hits),
		},
		Explanation: expl,
	}, true
}

// ── R08 Amount anomaly ─────────────────────────────────────────────

type r08 struct{}

func (r08) Code() string { return "R08" }
func (r08) Name() string { return "Amount anomaly (аномальная сумма операции)" }
func (r08) Weight() int  { return 10 }

var r08Multiplier = decimal.RequireFromString("5")

const r08MinHistoryCount = 5

func (r r08) Check(d domain.Dossier) (domain.RuleResult, bool) {
	sorted := sortedByTime(d.Transactions)
	if len(sorted) < r08MinHistoryCount+1 {
		return domain.RuleResult{}, false
	}
	latest := sorted[len(sorted)-1]

	history := make([]decimal.Decimal, 0, len(sorted)-1)
	for _, t := range sorted[:len(sorted)-1] {
		history = append(history, t.Amount)
	}
	sort.Slice(history, func(i, j int) bool { return history[i].Cmp(history[j]) < 0 })

	median := decimalMedian(history)
	if median.IsZero() {
		return domain.RuleResult{}, false
	}
	ratio := latest.Amount.Div(median)
	if ratio.Cmp(r08Multiplier) < 0 {
		return domain.RuleResult{}, false
	}
	expl := fmt.Sprintf(
		"Сумма последней операции (%s) в %.1f раз выше типичной для клиента (медиана %s).",
		fmtAmount(latest.Amount), ratio.InexactFloat64(), fmtAmount(median))

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"transaction_id":    latest.TransactionID,
			"amount":            latest.Amount,
			"historical_median": median,
			"ratio":             ratio.Round(1),
		},
		Explanation: expl,
	}, true
}

func decimalMedian(sorted []decimal.Decimal) decimal.Decimal {
	n := len(sorted)
	if n == 0 {
		return decimal.Zero
	}
	if n%2 == 1 {
		return sorted[n/2]
	}
	return sorted[n/2-1].Add(sorted[n/2]).Div(decimal.NewFromInt(2))
}

// ── R09 Fan-in / Fan-out ───────────────────────────────────────────

type r09 struct{}

func (r09) Code() string { return "R09" }
func (r09) Name() string {
	return "Fan-in / Fan-out (сбор мелких сумм с последующим выводом)"
}
func (r09) Weight() int { return 15 }

const (
	r09Window   = 2 * 24 * time.Hour
	r09MinCount = 4
)

var r09Tolerance = decimal.RequireFromString("0.15")

func (r r09) Check(d domain.Dossier) (domain.RuleResult, bool) {
	sorted := sortedByTime(d.Transactions)

	for _, outgoing := range sorted {
		if outgoing.Direction != domain.DirectionOut {
			continue
		}
		windowStart := outgoing.Timestamp.Add(-r09Window)

		var incoming []domain.TxView
		for _, tx := range sorted {
			if tx.Direction != domain.DirectionIn {
				continue
			}
			if tx.Timestamp.Before(windowStart) || !tx.Timestamp.Before(outgoing.Timestamp) {
				continue
			}
			incoming = append(incoming, tx)
		}
		if len(incoming) < r09MinCount {
			continue
		}
		incomingTotal := sumAmounts(incoming)
		if isCloseAmount(outgoing.Amount, incomingTotal, r09Tolerance) {
			expl := fmt.Sprintf(
				"%d входящих операций на общую сумму %s были собраны и выведены одной операцией на %s.",
				len(incoming), fmtAmount(incomingTotal), fmtAmount(outgoing.Amount))
			return domain.RuleResult{
				Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
				Evidence: map[string]any{
					"outgoing_transaction_id":  outgoing.TransactionID,
					"incoming_transaction_ids": txIDs(incoming),
					"incoming_count":           len(incoming),
					"incoming_total":           incomingTotal,
					"outgoing_amount":          outgoing.Amount,
				},
				Explanation: expl,
			}, true
		}
	}
	return domain.RuleResult{}, false
}

// ── R10 High-risk counterparty ─────────────────────────────────────

type r10 struct{}

func (r10) Code() string { return "R10" }
func (r10) Name() string {
	return "High-risk counterparty (контрагент из чёрного списка)"
}
func (r10) Weight() int { return 25 }

func (r r10) Check(d domain.Dossier) (domain.RuleResult, bool) {
	var hits []domain.RelationView
	for _, rel := range d.Relations {
		if rel.CounterpartBlacklisted && rel.RelationType == domain.RelationFrequentCounterparty {
			hits = append(hits, rel)
		}
	}
	if len(hits) == 0 {
		return domain.RuleResult{}, false
	}
	first := hits[0]
	expl := fmt.Sprintf(
		"Клиент регулярно проводит операции с контрагентом «%s», который находится в чёрном списке банка.",
		first.CounterpartLabel)

	return domain.RuleResult{
		Code: r.Code(), Name: r.Name(), Weight: r.Weight(),
		Evidence: map[string]any{
			"counterpart_id":   first.CounterpartID,
			"counterpart_type": first.CounterpartType,
			"matches_count":    len(hits),
		},
		Explanation: expl,
	}, true
}
