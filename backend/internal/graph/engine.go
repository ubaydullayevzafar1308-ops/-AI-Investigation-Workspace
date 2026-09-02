package graph

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/shopspring/decimal"

	"github.com/tiredjon/cbu/backend/internal/domain"
	"github.com/tiredjon/cbu/backend/internal/repo"
)

const (
	maxSubgraphDepth = 2
)

var (
	suspiciousTotal   = decimal.RequireFromString("300000000")
	suspiciousTxCount = 10
)

// Engine строит подграф связей глубины 2 через рекурсивный CTE — без
// Neo4j, обычный PostgreSQL. См. ARCHITECTURE.md §7.
type Engine struct{}

func New() *Engine { return &Engine{} }

// NodeID формирует id узла "{type}_{dbId}".
func NodeID(typ string, id int64) string { return typ + "_" + strconv.FormatInt(id, 10) }

func parseNodeID(id string) (string, int64) {
	i := strings.IndexByte(id, '_')
	typ := id[:i]
	n, _ := strconv.ParseInt(id[i+1:], 10, 64)
	return typ, n
}

// MoneyFlowGraph — пара (рёбра, метки), готовая для CycleDetector.
type MoneyFlowGraph struct {
	Edges  []MoneyEdge
	Labels map[string]string
}

type ownerRef struct {
	typ string
	id  int64
}

// Build — полный граф для фронта (GET /api/cases/{id}/graph).
func (e *Engine) Build(ctx context.Context, q *repo.Queries, clientID int64) (domain.Graph, error) {
	db := q.Raw()

	relationRows, err := fetchRelationSubgraph(ctx, db, clientID)
	if err != nil {
		return domain.Graph{}, err
	}
	mf, err := e.BuildMoneyFlowGraph(ctx, q, clientID)
	if err != nil {
		return domain.Graph{}, err
	}

	detector := NewCycleDetector(mf.Edges, mf.Labels)
	cycleEdgeIdx := detector.FindCycleEdgesFrom(NodeID("client", clientID))

	order := []string{}
	nodes := map[string]domain.GraphNode{}
	putNode := func(n domain.GraphNode) {
		if _, ok := nodes[n.ID]; !ok {
			order = append(order, n.ID)
		}
		nodes[n.ID] = n
	}
	putNodeIfAbsent := func(n domain.GraphNode) {
		if _, ok := nodes[n.ID]; !ok {
			order = append(order, n.ID)
			nodes[n.ID] = n
		}
	}

	root := NodeID("client", clientID)
	putNode(domain.GraphNode{ID: root, Type: "client", Label: fmt.Sprintf("Клиент #%d", clientID)})

	var edges []domain.GraphEdge
	for _, r := range relationRows {
		src := NodeID(r.sourceType, r.sourceID)
		dst := NodeID(r.targetType, r.targetID)
		putNodeIfAbsent(placeholderNode(r.sourceType, r.sourceID))
		putNodeIfAbsent(placeholderNode(r.targetType, r.targetID))
		rt := r.relationType
		edges = append(edges, domain.GraphEdge{Source: src, Target: dst, Kind: "relation", RelationType: &rt})
	}

	for i, flow := range mf.Edges {
		ft, fid := parseNodeID(flow.FromNodeID)
		tt, tid := parseNodeID(flow.ToNodeID)
		putNodeIfAbsent(placeholderNode(ft, fid))
		putNodeIfAbsent(placeholderNode(tt, tid))

		suspicious := cycleEdgeIdx[i] ||
			flow.Amount.Cmp(suspiciousTotal) >= 0 ||
			flow.Count >= suspiciousTxCount

		total := flow.Amount
		count := flow.Count
		s := suspicious
		edges = append(edges, domain.GraphEdge{
			Source: flow.FromNodeID, Target: flow.ToNodeID, Kind: "money_flow",
			Total: &total, Count: &count, Suspicious: &s,
		})
	}

	if err := enrichNodeLabelsAndFlags(ctx, db, nodes); err != nil {
		return domain.Graph{}, err
	}

	outNodes := make([]domain.GraphNode, 0, len(order))
	for _, id := range order {
		outNodes = append(outNodes, nodes[id])
	}
	return domain.Graph{Nodes: outNodes, Edges: edges}, nil
}

// BuildMoneyFlowGraph — BFS глубины 2 через ДВА источника соседей
// (relationships И transactions), затем агрегирует направленные потоки
// между всеми парами субъектов подграфа. Вызывается Data Collector'ом
// до Rule Engine, чтобы R03 читал готовые циклы из Dossier.MoneyCycles.
func (e *Engine) BuildMoneyFlowGraph(ctx context.Context, q *repo.Queries, clientID int64) (MoneyFlowGraph, error) {
	db := q.Raw()
	root := ownerRef{"client", clientID}

	discovered := map[ownerRef]bool{root: true}
	frontier := []ownerRef{root}

	for depth := 0; depth < maxSubgraphDepth && len(frontier) > 0; depth++ {
		var next []ownerRef
		for _, owner := range frontier {
			neigh, err := fetchDirectRelationNeighbors(ctx, db, owner)
			if err != nil {
				return MoneyFlowGraph{}, err
			}
			txNeigh, err := fetchDirectTransactionNeighbors(ctx, db, owner)
			if err != nil {
				return MoneyFlowGraph{}, err
			}
			for _, n := range append(neigh, txNeigh...) {
				if !discovered[n] {
					discovered[n] = true
					next = append(next, n)
				}
			}
		}
		frontier = next
	}

	labels := map[string]string{
		NodeID("client", clientID): fmt.Sprintf("Клиент #%d", clientID),
	}
	if len(discovered) <= 1 {
		return MoneyFlowGraph{Edges: nil, Labels: labels}, nil
	}

	owners := make([]ownerRef, 0, len(discovered))
	for o := range discovered {
		owners = append(owners, o)
	}

	flowRows, err := fetchDirectedMoneyFlowsWithinSubgraph(ctx, db, owners)
	if err != nil {
		return MoneyFlowGraph{}, err
	}

	var edges []MoneyEdge
	for _, fr := range flowRows {
		from := NodeID(fr.fromType, fr.fromID)
		to := NodeID(fr.toType, fr.toID)
		if _, ok := labels[from]; !ok {
			labels[from] = from
		}
		if _, ok := labels[to]; !ok {
			labels[to] = to
		}
		edges = append(edges, MoneyEdge{FromNodeID: from, ToNodeID: to, Amount: fr.total, Count: fr.count})
	}

	if err := enrichLabelsOnly(ctx, db, labels); err != nil {
		return MoneyFlowGraph{}, err
	}
	return MoneyFlowGraph{Edges: edges, Labels: labels}, nil
}

func placeholderNode(typ string, id int64) domain.GraphNode {
	nid := NodeID(typ, id)
	return domain.GraphNode{ID: nid, Type: typ, Label: nid}
}

// ── нативные запросы ─────────────────────────────────────────────────

type relationRow struct {
	sourceType, targetType string
	sourceID, targetID     int64
	relationType           string
}

func fetchRelationSubgraph(ctx context.Context, db repo.DBTX, clientID int64) ([]relationRow, error) {
	const sql = `
		WITH RECURSIVE graph AS (
		    SELECT source_type, source_id, target_type, target_id, relation_type, 1 AS depth
		    FROM relationships
		    WHERE (source_type = 'client' AND source_id = $1)
		       OR (target_type = 'client' AND target_id = $1)
		  UNION
		    SELECT r.source_type, r.source_id, r.target_type, r.target_id, r.relation_type, g.depth + 1
		    FROM relationships r
		    JOIN graph g ON (r.source_type = g.target_type AND r.source_id = g.target_id)
		    WHERE g.depth < $2
		)
		SELECT DISTINCT source_type, source_id, target_type, target_id, relation_type FROM graph`
	rows, err := db.Query(ctx, sql, clientID, maxSubgraphDepth)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []relationRow
	for rows.Next() {
		var r relationRow
		if err := rows.Scan(&r.sourceType, &r.sourceID, &r.targetType, &r.targetID, &r.relationType); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func fetchDirectRelationNeighbors(ctx context.Context, db repo.DBTX, o ownerRef) ([]ownerRef, error) {
	const sql = `
		SELECT
		    CASE WHEN source_type = $1 AND source_id = $2 THEN target_type ELSE source_type END,
		    CASE WHEN source_type = $1 AND source_id = $2 THEN target_id   ELSE source_id   END
		FROM relationships
		WHERE (source_type = $1 AND source_id = $2) OR (target_type = $1 AND target_id = $2)`
	return scanOwnerRefs(ctx, db, sql, o.typ, o.id)
}

func fetchDirectTransactionNeighbors(ctx context.Context, db repo.DBTX, o ownerRef) ([]ownerRef, error) {
	const sql = `
		SELECT DISTINCT counterpart.owner_type, counterpart.owner_id
		FROM transactions t
		JOIN accounts own ON own.id IN (t.from_account, t.to_account)
		               AND own.owner_type = $1 AND own.owner_id = $2
		JOIN accounts counterpart ON counterpart.id = (
		    CASE WHEN t.from_account = own.id THEN t.to_account ELSE t.from_account END
		)
		WHERE NOT (counterpart.owner_type = $1 AND counterpart.owner_id = $2)`
	return scanOwnerRefs(ctx, db, sql, o.typ, o.id)
}

func scanOwnerRefs(ctx context.Context, db repo.DBTX, sql string, args ...any) ([]ownerRef, error) {
	rows, err := db.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ownerRef
	for rows.Next() {
		var r ownerRef
		if err := rows.Scan(&r.typ, &r.id); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

type flowRow struct {
	fromType, toType string
	fromID, toID     int64
	total            decimal.Decimal
	count            int
}

func fetchDirectedMoneyFlowsWithinSubgraph(ctx context.Context, db repo.DBTX, owners []ownerRef) ([]flowRow, error) {
	if len(owners) == 0 {
		return nil, nil
	}
	var vals []string
	var args []any
	for i, o := range owners {
		if o.typ != "client" && o.typ != "company" {
			return nil, fmt.Errorf("unexpected owner_type in subgraph: %s", o.typ)
		}
		vals = append(vals, fmt.Sprintf("($%d::text, $%d::bigint)", i*2+1, i*2+2))
		args = append(args, o.typ, o.id)
	}
	sql := `
		WITH subgraph_owners(owner_type, owner_id) AS (
		    VALUES ` + strings.Join(vals, ", ") + `
		)
		SELECT
		    fo.owner_type, fo.owner_id, to_.owner_type, to_.owner_id,
		    SUM(t.amount), COUNT(*)
		FROM transactions t
		JOIN accounts fa ON fa.id = t.from_account
		JOIN accounts ta ON ta.id = t.to_account
		JOIN subgraph_owners fo  ON fo.owner_type  = fa.owner_type AND fo.owner_id  = fa.owner_id
		JOIN subgraph_owners to_ ON to_.owner_type = ta.owner_type AND to_.owner_id = ta.owner_id
		WHERE NOT (fo.owner_type = to_.owner_type AND fo.owner_id = to_.owner_id)
		GROUP BY fo.owner_type, fo.owner_id, to_.owner_type, to_.owner_id`
	rows, err := db.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []flowRow
	for rows.Next() {
		var f flowRow
		if err := rows.Scan(&f.fromType, &f.fromID, &f.toType, &f.toID, &f.total, &f.count); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func enrichLabelsOnly(ctx context.Context, db repo.DBTX, labels map[string]string) error {
	var clientIDs, companyIDs []int64
	for nodeID := range labels {
		typ, id := parseNodeID(nodeID)
		switch typ {
		case "client":
			clientIDs = append(clientIDs, id)
		case "company":
			companyIDs = append(companyIDs, id)
		}
	}
	apply := func(ids []int64, table, labelCol, typ string) error {
		if len(ids) == 0 {
			return nil
		}
		rows, err := db.Query(ctx, `SELECT id, `+labelCol+` FROM `+table+` WHERE id = ANY($1)`, ids)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id int64
			var label string
			if err := rows.Scan(&id, &label); err != nil {
				return err
			}
			labels[NodeID(typ, id)] = label
		}
		return rows.Err()
	}
	if err := apply(clientIDs, "clients", "full_name", "client"); err != nil {
		return err
	}
	return apply(companyIDs, "companies", "name", "company")
}

func enrichNodeLabelsAndFlags(ctx context.Context, db repo.DBTX, nodes map[string]domain.GraphNode) error {
	var clientIDs, companyIDs []int64
	for _, n := range nodes {
		_, id := parseNodeID(n.ID)
		switch n.Type {
		case "client":
			clientIDs = append(clientIDs, id)
		case "company":
			companyIDs = append(companyIDs, id)
		}
	}
	apply := func(ids []int64, table, labelCol, typ string) error {
		if len(ids) == 0 {
			return nil
		}
		rows, err := db.Query(ctx, `SELECT id, `+labelCol+`, is_blacklisted FROM `+table+` WHERE id = ANY($1)`, ids)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id int64
			var label string
			var flagged bool
			if err := rows.Scan(&id, &label, &flagged); err != nil {
				return err
			}
			nid := NodeID(typ, id)
			nodes[nid] = domain.GraphNode{ID: nid, Type: typ, Label: label, Flagged: flagged}
		}
		return rows.Err()
	}
	if err := apply(clientIDs, "clients", "full_name", "client"); err != nil {
		return err
	}
	return apply(companyIDs, "companies", "name", "company")
}
