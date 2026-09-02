package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

// ── alerts ───────────────────────────────────────────────────────────

func scanAlert(row pgx.Row) (domain.Alert, error) {
	var a domain.Alert
	err := row.Scan(&a.ID, &a.TransactionID, &a.ClientID, &a.TriggerReason,
		&a.Severity, &a.Status, &a.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Alert{}, ErrNotFound
	}
	return a, err
}

const alertCols = `id, transaction_id, client_id, trigger_reason, severity, status, created_at`

func (q *Queries) GetAlert(ctx context.Context, id int64) (domain.Alert, error) {
	return scanAlert(q.db.QueryRow(ctx, `SELECT `+alertCols+` FROM alerts WHERE id = $1`, id))
}

func (q *Queries) ListAlertsByClient(ctx context.Context, clientID int64) ([]domain.Alert, error) {
	rows, err := q.db.Query(ctx, `SELECT `+alertCols+` FROM alerts WHERE client_id = $1 ORDER BY id`, clientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Alert
	for rows.Next() {
		a, err := scanAlert(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// SearchAlerts — опциональные фильтры status/severity (AND), сортировка
// created_at desc, пагинация. Возвращает страницу и общее число.
func (q *Queries) SearchAlerts(ctx context.Context, status, severity *string, limit, offset int) ([]domain.Alert, int64, error) {
	where := `WHERE ($1::text IS NULL OR status = $1) AND ($2::text IS NULL OR severity = $2)`

	var total int64
	if err := q.db.QueryRow(ctx, `SELECT COUNT(*) FROM alerts `+where, status, severity).Scan(&total); err != nil {
		return nil, 0, err
	}

	rows, err := q.db.Query(ctx, `SELECT `+alertCols+` FROM alerts `+where+
		` ORDER BY created_at DESC, id DESC LIMIT $3 OFFSET $4`, status, severity, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []domain.Alert
	for rows.Next() {
		a, err := scanAlert(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, a)
	}
	return out, total, rows.Err()
}

func (q *Queries) UpdateAlertStatus(ctx context.Context, id int64, status string) error {
	_, err := q.db.Exec(ctx, `UPDATE alerts SET status = $2 WHERE id = $1`, id, status)
	return err
}

// ── cases ────────────────────────────────────────────────────────────

const caseCols = `id, alert_id, client_id, risk_score, risk_level, status, analyst_decision,
	dossier_json, evidence_json, explanation_json, created_at, closed_at`

func scanCase(row pgx.Row) (domain.Case, error) {
	var c domain.Case
	err := row.Scan(&c.ID, &c.AlertID, &c.ClientID, &c.RiskScore, &c.RiskLevel, &c.Status,
		&c.AnalystDecision, &c.DossierJSON, &c.EvidenceJSON, &c.ExplanationJSON, &c.CreatedAt, &c.ClosedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Case{}, ErrNotFound
	}
	return c, err
}

func (q *Queries) GetCase(ctx context.Context, id int64) (domain.Case, error) {
	return scanCase(q.db.QueryRow(ctx, `SELECT `+caseCols+` FROM cases WHERE id = $1`, id))
}

func (q *Queries) GetCaseByAlert(ctx context.Context, alertID int64) (domain.Case, error) {
	return scanCase(q.db.QueryRow(ctx, `SELECT `+caseCols+` FROM cases WHERE alert_id = $1`, alertID))
}

// InsertCase создаёт открытый кейс, возвращает id и created_at.
func (q *Queries) InsertCase(ctx context.Context, alertID, clientID int64) (int64, time.Time, error) {
	var id int64
	var createdAt time.Time
	err := q.db.QueryRow(ctx, `
		INSERT INTO cases (alert_id, client_id, status, created_at)
		VALUES ($1, $2, 'open', NOW())
		RETURNING id, created_at`, alertID, clientID).Scan(&id, &createdAt)
	return id, createdAt, err
}

// UpdateCaseSnapshots записывает финальные результаты пайплайна.
func (q *Queries) UpdateCaseSnapshots(ctx context.Context, id int64, score int, level string, dossier, evidence, explanation []byte) error {
	_, err := q.db.Exec(ctx, `
		UPDATE cases SET risk_score = $2, risk_level = $3,
		    dossier_json = $4, evidence_json = $5, explanation_json = $6
		WHERE id = $1`, id, score, level, dossier, evidence, explanation)
	return err
}

func (q *Queries) UpdateCaseDecision(ctx context.Context, id int64, status, comment string) (domain.Case, error) {
	return scanCase(q.db.QueryRow(ctx, `
		UPDATE cases SET status = $2, analyst_decision = $3, closed_at = NOW()
		WHERE id = $1
		RETURNING `+caseCols, id, status, comment))
}

func (q *Queries) ListCasesPaged(ctx context.Context, limit, offset int) ([]domain.Case, int64, error) {
	var total int64
	if err := q.db.QueryRow(ctx, `SELECT COUNT(*) FROM cases`).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := q.db.Query(ctx, `SELECT `+caseCols+` FROM cases ORDER BY created_at DESC, id DESC LIMIT $1 OFFSET $2`, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []domain.Case
	for rows.Next() {
		c, err := scanCase(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, c)
	}
	return out, total, rows.Err()
}

func (q *Queries) CountCases(ctx context.Context) (int64, error) {
	var n int64
	err := q.db.QueryRow(ctx, `SELECT COUNT(*) FROM cases`).Scan(&n)
	return n, err
}

func (q *Queries) CountCasesSince(ctx context.Context, since time.Time) (int64, error) {
	var n int64
	err := q.db.QueryRow(ctx, `SELECT COUNT(*) FROM cases WHERE created_at >= $1`, since).Scan(&n)
	return n, err
}

func (q *Queries) AverageRiskScore(ctx context.Context) (*float64, error) {
	var avg *float64
	err := q.db.QueryRow(ctx, `SELECT AVG(risk_score) FROM cases`).Scan(&avg)
	return avg, err
}

// ── rule_hits ────────────────────────────────────────────────────────

func (q *Queries) InsertRuleHit(ctx context.Context, caseID int64, code, name string, weight int, evidenceJSON []byte, explanation string) error {
	_, err := q.db.Exec(ctx, `
		INSERT INTO rule_hits (case_id, rule_code, rule_name, weight, evidence_json, explanation, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, NOW())`, caseID, code, name, weight, evidenceJSON, explanation)
	return err
}

func (q *Queries) CountByRuleCode(ctx context.Context) (map[string]int64, error) {
	rows, err := q.db.Query(ctx, `SELECT rule_code, COUNT(*) FROM rule_hits GROUP BY rule_code`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var code string
		var n int64
		if err := rows.Scan(&code, &n); err != nil {
			return nil, err
		}
		out[code] = n
	}
	return out, rows.Err()
}

// ── reports ──────────────────────────────────────────────────────────

func (q *Queries) GetReportByCase(ctx context.Context, caseID int64) (domain.Report, error) {
	row := q.db.QueryRow(ctx, `
		SELECT id, case_id, draft_text, final_text, generated_at, approved_by
		FROM reports WHERE case_id = $1`, caseID)
	var r domain.Report
	err := row.Scan(&r.ID, &r.CaseID, &r.DraftText, &r.FinalText, &r.GeneratedAt, &r.ApprovedBy)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Report{}, ErrNotFound
	}
	return r, err
}

func (q *Queries) InsertReportDraft(ctx context.Context, caseID int64, draft string) error {
	_, err := q.db.Exec(ctx, `
		INSERT INTO reports (case_id, draft_text, generated_at)
		VALUES ($1, $2, NOW())`, caseID, draft)
	return err
}

func (q *Queries) UpdateReportFinal(ctx context.Context, caseID int64, finalText, approvedBy string) (domain.Report, error) {
	row := q.db.QueryRow(ctx, `
		UPDATE reports SET final_text = $2, approved_by = $3
		WHERE case_id = $1
		RETURNING id, case_id, draft_text, final_text, generated_at, approved_by`, caseID, finalText, approvedBy)
	var r domain.Report
	err := row.Scan(&r.ID, &r.CaseID, &r.DraftText, &r.FinalText, &r.GeneratedAt, &r.ApprovedBy)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Report{}, ErrNotFound
	}
	return r, err
}

// ── audit_log ────────────────────────────────────────────────────────

const auditCols = `id, case_id, event_type, rules_version, risk_score, llm_provider,
	llm_model, llm_prompt, llm_response, actor, created_at`

func scanAudit(row pgx.Row) (domain.AuditLog, error) {
	var a domain.AuditLog
	err := row.Scan(&a.ID, &a.CaseID, &a.EventType, &a.RulesVersion, &a.RiskScore,
		&a.LLMProvider, &a.LLMModel, &a.LLMPrompt, &a.LLMResponse, &a.Actor, &a.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.AuditLog{}, ErrNotFound
	}
	return a, err
}

func (q *Queries) InsertAudit(ctx context.Context, a domain.AuditLog) error {
	_, err := q.db.Exec(ctx, `
		INSERT INTO audit_log (case_id, event_type, rules_version, risk_score,
		    llm_provider, llm_model, llm_prompt, llm_response, actor, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW())`,
		a.CaseID, a.EventType, a.RulesVersion, a.RiskScore,
		a.LLMProvider, a.LLMModel, a.LLMPrompt, a.LLMResponse, a.Actor)
	return err
}

func (q *Queries) ListAuditByCase(ctx context.Context, caseID int64) ([]domain.AuditLog, error) {
	rows, err := q.db.Query(ctx, `SELECT `+auditCols+` FROM audit_log WHERE case_id = $1 ORDER BY created_at ASC, id ASC`, caseID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.AuditLog
	for rows.Next() {
		a, err := scanAudit(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (q *Queries) GetAuditByIDAndCase(ctx context.Context, id, caseID int64) (domain.AuditLog, error) {
	return scanAudit(q.db.QueryRow(ctx, `SELECT `+auditCols+` FROM audit_log WHERE id = $1 AND case_id = $2`, id, caseID))
}

// FirstAuditByEvent — первая по id запись данного типа для кейса
// (используется идемпотентным повтором buildCase: первый llm_called —
// это ответ explainRisk).
func (q *Queries) FirstAuditByEvent(ctx context.Context, caseID int64, eventType string) (domain.AuditLog, error) {
	return scanAudit(q.db.QueryRow(ctx, `SELECT `+auditCols+`
		FROM audit_log WHERE case_id = $1 AND event_type = $2 ORDER BY id ASC LIMIT 1`, caseID, eventType))
}

// ── feature_flags ────────────────────────────────────────────────────

func (q *Queries) ListFeatureFlags(ctx context.Context) ([]domain.FeatureFlag, error) {
	rows, err := q.db.Query(ctx, `SELECT id, module_code, module_name, enabled, description FROM feature_flags ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.FeatureFlag
	for rows.Next() {
		var f domain.FeatureFlag
		if err := rows.Scan(&f.ID, &f.ModuleCode, &f.ModuleName, &f.Enabled, &f.Description); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (q *Queries) GetFeatureFlag(ctx context.Context, code string) (domain.FeatureFlag, error) {
	row := q.db.QueryRow(ctx, `SELECT id, module_code, module_name, enabled, description FROM feature_flags WHERE module_code = $1`, code)
	var f domain.FeatureFlag
	err := row.Scan(&f.ID, &f.ModuleCode, &f.ModuleName, &f.Enabled, &f.Description)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.FeatureFlag{}, ErrNotFound
	}
	return f, err
}
