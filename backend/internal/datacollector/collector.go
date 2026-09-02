// Package datacollector — Data Collector (① в пайплайне). Единственное
// место, где сырые данные из репозиториев собираются в domain.Dossier —
// единственный вход для Rule Engine (②).
package datacollector

import (
	"context"
	"fmt"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/graph"
	"github.com/tiredjon/cbu/backend/internal/repo"
)

type Service struct {
	graph *graph.Engine
}

func New(graphEngine *graph.Engine) *Service {
	return &Service{graph: graphEngine}
}

// Collect собирает досье по клиенту алерта.
func (s *Service) Collect(ctx context.Context, q *repo.Queries, alertID int64) (domain.Dossier, error) {
	alert, err := q.GetAlert(ctx, alertID)
	if err != nil {
		return domain.Dossier{}, fmt.Errorf("alert not found: %d", alertID)
	}
	if alert.ClientID == nil {
		return domain.Dossier{}, fmt.Errorf("alert %d has no associated client", alertID)
	}
	return s.CollectForClient(ctx, q, *alert.ClientID)
}

// CollectForClient — повторная сборка досье того же клиента.
func (s *Service) CollectForClient(ctx context.Context, q *repo.Queries, clientID int64) (domain.Dossier, error) {
	client, err := q.GetClient(ctx, clientID)
	if err != nil {
		return domain.Dossier{}, fmt.Errorf("client not found: %d", clientID)
	}

	accounts, err := q.ListAccountsByOwner(ctx, domain.OwnerClient, clientID)
	if err != nil {
		return domain.Dossier{}, err
	}
	accountIDs := make([]int64, 0, len(accounts))
	accountSet := map[int64]bool{}
	for _, a := range accounts {
		accountIDs = append(accountIDs, a.ID)
		accountSet[a.ID] = true
	}

	var txViews []domain.TxView
	if len(accountIDs) > 0 {
		raw, err := q.ListTransactionsByAccountIDs(ctx, accountIDs)
		if err != nil {
			return domain.Dossier{}, err
		}
		txViews = buildTxViews(accountSet, raw)
	}

	relations, err := s.buildRelationViews(ctx, q, clientID)
	if err != nil {
		return domain.Dossier{}, err
	}
	relatedCompanies, err := s.buildRelatedCompanies(ctx, q, clientID)
	if err != nil {
		return domain.Dossier{}, err
	}
	pastAlerts, err := s.buildPastAlerts(ctx, q, clientID)
	if err != nil {
		return domain.Dossier{}, err
	}
	moneyCycles, err := s.detectMoneyCycles(ctx, q, clientID)
	if err != nil {
		return domain.Dossier{}, err
	}

	d := domain.Dossier{
		ClientID:         client.ID,
		FullName:         client.FullName,
		ClientType:       client.ClientType,
		Blacklisted:      client.Blacklisted,
		AccountIDs:       accountIDs,
		Transactions:     txViews,
		Relations:        relations,
		RelatedCompanies: relatedCompanies,
		PastAlerts:       pastAlerts,
		MoneyCycles:      moneyCycles,
	}
	d.RegistrationDate = &client.RegistrationDate
	if client.INN != nil {
		d.INN = *client.INN
	}
	if client.Phone != nil {
		d.Phone = *client.Phone
	}
	if client.DeviceID != nil {
		d.DeviceID = *client.DeviceID
	}
	if client.Address != nil {
		d.Address = *client.Address
	}
	return d, nil
}

func buildTxViews(clientAccounts map[int64]bool, raw []domain.Transaction) []domain.TxView {
	out := make([]domain.TxView, 0, len(raw))
	for _, tx := range raw {
		var fromID *int64 = tx.FromAccountID
		var toID *int64 = tx.ToAccountID

		// direction — с точки зрения счетов клиента: если его счёт
		// отправитель -> out, иначе -> in (перевод между своими счетами
		// считаем out, как условно исходящую операцию).
		dir := domain.DirectionIn
		if fromID != nil && clientAccounts[*fromID] {
			dir = domain.DirectionOut
		}

		out = append(out, domain.TxView{
			TransactionID: tx.ID,
			FromAccountID: fromID,
			ToAccountID:   toID,
			Direction:     dir,
			Amount:        tx.Amount,
			Currency:      tx.Currency,
			TxType:        tx.TxType,
			Timestamp:     tx.TxTimestamp,
		})
	}
	return out
}

func (s *Service) buildRelationViews(ctx context.Context, q *repo.Queries, clientID int64) ([]domain.RelationView, error) {
	asSource, err := q.ListRelationshipsBySource(ctx, domain.OwnerClient, clientID)
	if err != nil {
		return nil, err
	}
	asTarget, err := q.ListRelationshipsByTarget(ctx, domain.OwnerClient, clientID)
	if err != nil {
		return nil, err
	}

	var out []domain.RelationView
	for _, r := range asSource {
		v, err := s.toRelationView(ctx, q, r.TargetType, r.TargetID, r.RelationType)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	for _, r := range asTarget {
		v, err := s.toRelationView(ctx, q, r.SourceType, r.SourceID, r.RelationType)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func (s *Service) toRelationView(ctx context.Context, q *repo.Queries, counterpartType string, counterpartID int64, relationType string) (domain.RelationView, error) {
	var label string
	var blacklisted bool
	if counterpartType == domain.OwnerClient {
		c, err := q.GetClient(ctx, counterpartID)
		if err == nil {
			label, blacklisted = c.FullName, c.Blacklisted
		} else {
			label = fmt.Sprintf("Клиент #%d", counterpartID)
		}
	} else {
		c, err := q.GetCompany(ctx, counterpartID)
		if err == nil {
			label, blacklisted = c.Name, c.Blacklisted
		} else {
			label = fmt.Sprintf("Компания #%d", counterpartID)
		}
	}
	return domain.RelationView{
		CounterpartType:        counterpartType,
		CounterpartID:          counterpartID,
		CounterpartLabel:       label,
		RelationType:           relationType,
		CounterpartBlacklisted: blacklisted,
	}, nil
}

func (s *Service) buildRelatedCompanies(ctx context.Context, q *repo.Queries, clientID int64) ([]domain.CompanyView, error) {
	companies, err := q.ListCompaniesByDirector(ctx, clientID)
	if err != nil {
		return nil, err
	}
	var out []domain.CompanyView
	for _, c := range companies {
		turnover, err := q.SumTurnoverByOwner(ctx, domain.OwnerCompany, c.ID)
		if err != nil {
			return nil, err
		}
		out = append(out, domain.CompanyView{
			CompanyID:        c.ID,
			Name:             c.Name,
			RegistrationDate: c.RegistrationDate,
			Blacklisted:      c.Blacklisted,
			RoleOfClient:     "director",
			TurnoverAmount:   turnover,
		})
	}
	return out, nil
}

func (s *Service) buildPastAlerts(ctx context.Context, q *repo.Queries, clientID int64) ([]domain.PastAlertView, error) {
	alerts, err := q.ListAlertsByClient(ctx, clientID)
	if err != nil {
		return nil, err
	}
	var out []domain.PastAlertView
	for _, a := range alerts {
		v := domain.PastAlertView{AlertID: a.ID, CreatedAt: a.CreatedAt}
		relatedCase, err := q.GetCaseByAlert(ctx, a.ID)
		if err == nil {
			id := relatedCase.ID
			status := relatedCase.Status
			v.CaseID = &id
			v.Status = &status
		}
		out = append(out, v)
	}
	return out, nil
}

func (s *Service) detectMoneyCycles(ctx context.Context, q *repo.Queries, clientID int64) ([]domain.CycleView, error) {
	mf, err := s.graph.BuildMoneyFlowGraph(ctx, q, clientID)
	if err != nil {
		return nil, err
	}
	if len(mf.Edges) == 0 {
		return nil, nil
	}
	detector := graph.NewCycleDetector(mf.Edges, mf.Labels)
	return detector.FindCyclesFrom(graph.NodeID("client", clientID)), nil
}
