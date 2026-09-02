// Package repo — слой доступа к данным на pgx. Заменяет Spring Data JPA
// репозитории. Каждый метод, участвующий в транзакции, принимает
// *Queries: контроллеры держат экземпляр на пуле, Case Builder создаёт
// экземпляр на pgx.Tx (см. WithTx).
package repo

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

// ErrNotFound — аналог Optional.empty() / EntityNotFound.
var ErrNotFound = errors.New("not found")

// DBTX реализуется и *pgxpool.Pool, и pgx.Tx.
type DBTX interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

type Queries struct {
	db DBTX
}

func New(db DBTX) *Queries { return &Queries{db: db} }

// WithTx возвращает Queries, работающий поверх переданной транзакции.
func (q *Queries) WithTx(tx pgx.Tx) *Queries { return &Queries{db: tx} }

// Pool даёт удобный доступ к нижележащему пулу для BeginTx в Case Builder.
type Pool = pgxpool.Pool

// ── clients ──────────────────────────────────────────────────────────

func (q *Queries) GetClient(ctx context.Context, id int64) (domain.Client, error) {
	row := q.db.QueryRow(ctx, `
		SELECT id, full_name, birth_date, inn, phone, device_id, address,
		       registration_date, client_type, risk_level, is_blacklisted, created_at
		FROM clients WHERE id = $1`, id)
	var c domain.Client
	err := row.Scan(&c.ID, &c.FullName, &c.BirthDate, &c.INN, &c.Phone, &c.DeviceID,
		&c.Address, &c.RegistrationDate, &c.ClientType, &c.RiskLevel, &c.Blacklisted, &c.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Client{}, ErrNotFound
	}
	return c, err
}

// ── companies ────────────────────────────────────────────────────────

func (q *Queries) GetCompany(ctx context.Context, id int64) (domain.Company, error) {
	row := q.db.QueryRow(ctx, `
		SELECT id, name, inn, address, registration_date, director_id, status, is_blacklisted, created_at
		FROM companies WHERE id = $1`, id)
	var c domain.Company
	err := row.Scan(&c.ID, &c.Name, &c.INN, &c.Address, &c.RegistrationDate,
		&c.DirectorID, &c.Status, &c.Blacklisted, &c.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Company{}, ErrNotFound
	}
	return c, err
}

func (q *Queries) ListCompaniesByDirector(ctx context.Context, directorID int64) ([]domain.Company, error) {
	rows, err := q.db.Query(ctx, `
		SELECT id, name, inn, address, registration_date, director_id, status, is_blacklisted, created_at
		FROM companies WHERE director_id = $1 ORDER BY id`, directorID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Company
	for rows.Next() {
		var c domain.Company
		if err := rows.Scan(&c.ID, &c.Name, &c.INN, &c.Address, &c.RegistrationDate,
			&c.DirectorID, &c.Status, &c.Blacklisted, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// ── accounts ─────────────────────────────────────────────────────────

func (q *Queries) ListAccountsByOwner(ctx context.Context, ownerType string, ownerID int64) ([]domain.Account, error) {
	rows, err := q.db.Query(ctx, `
		SELECT id, owner_type, owner_id, account_number, currency, opened_at, status, created_at
		FROM accounts WHERE owner_type = $1 AND owner_id = $2 ORDER BY id`, ownerType, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Account
	for rows.Next() {
		var a domain.Account
		if err := rows.Scan(&a.ID, &a.OwnerType, &a.OwnerID, &a.AccountNumber,
			&a.Currency, &a.OpenedAt, &a.Status, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// ── transactions ─────────────────────────────────────────────────────

func (q *Queries) ListTransactionsByAccountIDs(ctx context.Context, accountIDs []int64) ([]domain.Transaction, error) {
	rows, err := q.db.Query(ctx, `
		SELECT id, from_account, to_account, amount, currency, tx_type, description, tx_timestamp, created_at
		FROM transactions
		WHERE from_account = ANY($1) OR to_account = ANY($1)
		ORDER BY tx_timestamp ASC`, accountIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Transaction
	for rows.Next() {
		var t domain.Transaction
		if err := rows.Scan(&t.ID, &t.FromAccountID, &t.ToAccountID, &t.Amount,
			&t.Currency, &t.TxType, &t.Description, &t.TxTimestamp, &t.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// SumTurnoverByOwner — суммарный оборот по всем счетам владельца (для
// CompanyView.turnoverAmount, правило R04).
func (q *Queries) SumTurnoverByOwner(ctx context.Context, ownerType string, ownerID int64) (domain.Decimal, error) {
	row := q.db.QueryRow(ctx, `
		SELECT COALESCE(SUM(t.amount), 0)
		FROM transactions t
		LEFT JOIN accounts fa ON fa.id = t.from_account
		LEFT JOIN accounts ta ON ta.id = t.to_account
		WHERE (fa.owner_type = $1 AND fa.owner_id = $2)
		   OR (ta.owner_type = $1 AND ta.owner_id = $2)`, ownerType, ownerID)
	var d domain.Decimal
	err := row.Scan(&d)
	return d, err
}

// ── relationships ────────────────────────────────────────────────────

func (q *Queries) ListRelationshipsBySource(ctx context.Context, sourceType string, sourceID int64) ([]domain.Relationship, error) {
	return q.scanRelationships(ctx, `
		SELECT id, source_type, source_id, target_type, target_id, relation_type, confidence, created_at
		FROM relationships WHERE source_type = $1 AND source_id = $2`, sourceType, sourceID)
}

func (q *Queries) ListRelationshipsByTarget(ctx context.Context, targetType string, targetID int64) ([]domain.Relationship, error) {
	return q.scanRelationships(ctx, `
		SELECT id, source_type, source_id, target_type, target_id, relation_type, confidence, created_at
		FROM relationships WHERE target_type = $1 AND target_id = $2`, targetType, targetID)
}

func (q *Queries) scanRelationships(ctx context.Context, sql string, args ...any) ([]domain.Relationship, error) {
	rows, err := q.db.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Relationship
	for rows.Next() {
		var r domain.Relationship
		if err := rows.Scan(&r.ID, &r.SourceType, &r.SourceID, &r.TargetType, &r.TargetID,
			&r.RelationType, &r.Confidence, &r.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// Raw даёт сервисам (Graph Engine) прямой доступ к DBTX для нативных
// рекурсивных CTE, которые не выразить типовыми методами.
func (q *Queries) Raw() DBTX { return q.db }
