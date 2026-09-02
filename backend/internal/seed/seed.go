// Package seed — генератор синтетических данных (ARCHITECTURE.md §16).
// Порт uz.caseintel.seed.*: ~5000 клиентов / ~500 компаний / ~100k
// фоновых транзакций + 6 срежиссированных схем + golden case.
//
// ВНИМАНИЕ: предполагает ПУСТУЮ базу (свежие миграции без данных).
// Повторный запуск на непустой базе даст конфликты id.
package seed

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/tiredjon/cbu/backend/internal/config"
	"github.com/tiredjon/cbu/backend/internal/domain"
)

type builder struct {
	ctx   context.Context
	pool  *pgxpool.Pool
	r     *rand.Rand
	names *nameGen

	clientID, companyID, accountID, txID, relID, alertID int64
}

func (b *builder) nextClient() int64  { b.clientID++; return b.clientID }
func (b *builder) nextCompany() int64 { b.companyID++; return b.companyID }
func (b *builder) nextAccount() int64 { b.accountID++; return b.accountID }
func (b *builder) nextTx() int64      { b.txID++; return b.txID }
func (b *builder) nextRel() int64     { b.relID++; return b.relID }
func (b *builder) nextAlert() int64   { b.alertID++; return b.alertID }

// Run генерирует весь датасет.
func Run(ctx context.Context, pool *pgxpool.Pool, cfg config.SeedConfig) error {
	start := time.Now()
	b := &builder{
		ctx:  ctx,
		pool: pool,
		r:    rand.New(rand.NewSource(cfg.RandomSeed)),
	}
	b.names = newNameGen(b.r)

	log.Printf("seed: старт (clients=%d, companies=%d, transactions=%d)",
		cfg.ClientCount, cfg.CompanyCount, cfg.BackgroundTransactions)

	log.Println("seed: шаг 1/3 — фоновые клиенты/компании/счета")
	allAccountIDs, err := b.generateBackground(cfg.ClientCount, cfg.CompanyCount)
	if err != nil {
		return err
	}

	log.Printf("seed: шаг 2/3 — фоновые транзакции (%d)", cfg.BackgroundTransactions)
	if err := b.generateNormalTransactions(allAccountIDs, cfg.BackgroundTransactions); err != nil {
		return err
	}

	log.Println("seed: шаг 3/3 — 6 срежиссированных схем")
	structuring, err := b.structuringScheme()
	if err != nil {
		return err
	}
	circular, err := b.circularScheme()
	if err != nil {
		return err
	}
	transit, err := b.transitScheme()
	if err != nil {
		return err
	}
	spike, err := b.newCompanySpikeScheme()
	if err != nil {
		return err
	}
	fanio, err := b.fanInOutScheme()
	if err != nil {
		return err
	}
	golden, err := b.goldenCaseScheme()
	if err != nil {
		return err
	}

	if err := b.fixSequences(); err != nil {
		return err
	}

	log.Printf("seed: готово за %s", time.Since(start).Round(time.Second))
	log.Printf("Alert id для демо: structuring=%d, circular=%d, transit=%d, newCompanySpike=%d, fanInOut=%d, GOLDEN_CASE=%d",
		structuring, circular, transit, spike, fanio, golden)
	log.Printf("Запусти: curl -X POST http://localhost:8080/api/alerts/%d/investigate", golden)
	return nil
}

// ── фон ─────────────────────────────────────────────────────────────

func (b *builder) generateBackground(clientCount, companyCount int) ([]int64, error) {
	now := time.Now().UTC()

	// клиенты
	clientRows := make([][]any, 0, clientCount)
	clientIDs := make([]int64, 0, clientCount)
	for i := 0; i < clientCount; i++ {
		id := b.nextClient()
		clientIDs = append(clientIDs, id)
		male := b.r.Intn(2) == 0
		clientRows = append(clientRows, []any{
			id, b.names.fullName(male), b.randomBirthDate(), b.names.inn(id), b.names.phone(),
			b.names.deviceID(id), b.names.address(), b.randomPastDate(365 * 6),
			"individual", "low", false, now,
		})
	}
	if err := b.copy("clients",
		[]string{"id", "full_name", "birth_date", "inn", "phone", "device_id", "address",
			"registration_date", "client_type", "risk_level", "is_blacklisted", "created_at"},
		clientRows); err != nil {
		return nil, err
	}

	// компании
	companyRows := make([][]any, 0, companyCount)
	companyIDs := make([]int64, 0, companyCount)
	for i := 0; i < companyCount; i++ {
		id := b.nextCompany()
		companyIDs = append(companyIDs, id)
		var directorID any
		if len(clientIDs) > 0 {
			directorID = clientIDs[b.r.Intn(len(clientIDs))]
		}
		companyRows = append(companyRows, []any{
			id, b.names.companyName(id), b.names.inn(1_000_000 + id), b.names.address(),
			b.randomPastDate(365 * 8), directorID, "active", false, now,
		})
	}
	if err := b.copy("companies",
		[]string{"id", "name", "inn", "address", "registration_date", "director_id", "status", "is_blacklisted", "created_at"},
		companyRows); err != nil {
		return nil, err
	}

	// счета (1 на владельца)
	var accountRows [][]any
	var allAccountIDs []int64
	addAccounts := func(owners []int64, ownerType string) {
		for _, oid := range owners {
			id := b.nextAccount()
			allAccountIDs = append(allAccountIDs, id)
			accountRows = append(accountRows, []any{
				id, ownerType, oid, b.accountNumber(id), "UZS", b.randomPastDate(300), "active", now,
			})
		}
	}
	addAccounts(clientIDs, domain.OwnerClient)
	addAccounts(companyIDs, domain.OwnerCompany)
	if err := b.copy("accounts",
		[]string{"id", "owner_type", "owner_id", "account_number", "currency", "opened_at", "status", "created_at"},
		accountRows); err != nil {
		return nil, err
	}

	return allAccountIDs, nil
}

func (b *builder) generateNormalTransactions(accountIDs []int64, count int) error {
	if len(accountIDs) < 2 {
		return nil
	}
	txTypes := []string{domain.TxTypeTransfer, domain.TxTypeCardPayment, domain.TxTypeCashIn, domain.TxTypeCashOut}
	now := time.Now().UTC()

	const batchSize = 5000
	rows := make([][]any, 0, batchSize)
	flush := func() error {
		if len(rows) == 0 {
			return nil
		}
		err := b.copy("transactions",
			[]string{"id", "from_account", "to_account", "amount", "currency", "tx_type", "description", "tx_timestamp", "created_at"},
			rows)
		rows = rows[:0]
		return err
	}

	for i := 0; i < count; i++ {
		id := b.nextTx()
		from := accountIDs[b.r.Intn(len(accountIDs))]
		to := from
		for attempts := 0; to == from && attempts < 5; attempts++ {
			to = accountIDs[b.r.Intn(len(accountIDs))]
		}
		amount := int64(50_000 + b.r.Float64()*14_950_000)
		rows = append(rows, []any{
			id, from, to, amount, "UZS", txTypes[b.r.Intn(len(txTypes))], "Обычная операция",
			b.randomPastTimestamp(), now,
		})
		if len(rows) == batchSize {
			if err := flush(); err != nil {
				return err
			}
		}
	}
	return flush()
}

func (b *builder) copy(table string, cols []string, rows [][]any) error {
	_, err := b.pool.CopyFrom(b.ctx, pgx.Identifier{table}, cols, pgx.CopyFromRows(rows))
	if err != nil {
		return fmt.Errorf("copy into %s: %w", table, err)
	}
	return nil
}

// ── helpers для дат ─────────────────────────────────────────────────

func (b *builder) randomBirthDate() time.Time {
	return time.Date(1965+b.r.Intn(40), time.Month(1+b.r.Intn(12)), 1+b.r.Intn(28), 0, 0, 0, 0, time.UTC)
}

func (b *builder) randomPastDate(maxDaysAgo int) time.Time {
	return time.Now().UTC().Truncate(24*time.Hour).AddDate(0, 0, -(b.r.Intn(maxDaysAgo) + 1))
}

func (b *builder) randomPastTimestamp() time.Time {
	daysAgo := b.r.Intn(365)
	hour := 6 + b.r.Intn(16)
	t := time.Now().UTC().AddDate(0, 0, -daysAgo)
	return time.Date(t.Year(), t.Month(), t.Day(), hour, b.r.Intn(60), 0, 0, time.UTC)
}

func (b *builder) accountNumber(id int64) string { return fmt.Sprintf("2020%022d", id) }

// ── низкоуровневые вставки для схем ─────────────────────────────────

func (b *builder) insertClient(fullName, deviceID string, blacklisted bool) (int64, error) {
	id := b.nextClient()
	risk := "low"
	if blacklisted {
		risk = "high"
	}
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO clients (id, full_name, birth_date, inn, phone, device_id, address,
		    registration_date, client_type, risk_level, is_blacklisted, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,'individual',$9,$10,NOW())`,
		id, fullName, time.Date(1985, 5, 15, 0, 0, 0, 0, time.UTC), b.names.inn(id), b.names.phone(),
		deviceID, b.names.address(), time.Now().UTC().AddDate(-3, 0, 0), risk, blacklisted)
	return id, err
}

func (b *builder) insertCompany(name string, regDate time.Time, directorID *int64, blacklisted bool) (int64, error) {
	id := b.nextCompany()
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO companies (id, name, inn, address, registration_date, director_id, status, is_blacklisted, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,'active',$7,NOW())`,
		id, name, b.names.inn(2_000_000+id), b.names.address(), regDate, directorID, blacklisted)
	return id, err
}

func (b *builder) insertAccount(ownerType string, ownerID int64) (int64, error) {
	id := b.nextAccount()
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO accounts (id, owner_type, owner_id, account_number, currency, opened_at, status, created_at)
		VALUES ($1,$2,$3,$4,'UZS',$5,'active',NOW())`,
		id, ownerType, ownerID, fmt.Sprintf("3030%022d", id), time.Now().UTC().AddDate(0, -6, 0))
	return id, err
}

func (b *builder) insertTx(from, to int64, amount int64, txType string, ts time.Time, desc string) (int64, error) {
	id := b.nextTx()
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO transactions (id, from_account, to_account, amount, currency, tx_type, description, tx_timestamp, created_at)
		VALUES ($1,$2,$3,$4,'UZS',$5,$6,$7,NOW())`,
		id, from, to, amount, txType, desc, ts)
	return id, err
}

func (b *builder) insertRel(srcType string, srcID int64, tgtType string, tgtID int64, relType string) error {
	id := b.nextRel()
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO relationships (id, source_type, source_id, target_type, target_id, relation_type, confidence, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,1.00,NOW())`,
		id, srcType, srcID, tgtType, tgtID, relType)
	return err
}

func (b *builder) insertAlert(txID, clientID int64, reason, severity string) (int64, error) {
	id := b.nextAlert()
	_, err := b.pool.Exec(b.ctx, `
		INSERT INTO alerts (id, transaction_id, client_id, trigger_reason, severity, status, created_at)
		VALUES ($1,$2,$3,$4,$5,'new',NOW())`,
		id, txID, clientID, reason, severity)
	return id, err
}

func (b *builder) fixSequences() error {
	tables := []string{"clients", "companies", "accounts", "transactions", "relationships", "alerts",
		"cases", "rule_hits", "reports", "audit_log"}
	for _, t := range tables {
		_, err := b.pool.Exec(b.ctx, fmt.Sprintf(
			`SELECT setval(pg_get_serial_sequence('%s','id'), COALESCE((SELECT MAX(id) FROM %s), 0) + 1, false)`, t, t))
		if err != nil {
			return fmt.Errorf("fix sequence %s: %w", t, err)
		}
	}
	return nil
}

func ptr(v int64) *int64 { return &v }
