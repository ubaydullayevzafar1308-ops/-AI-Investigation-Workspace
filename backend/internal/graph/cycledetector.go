// Package graph — Graph Engine (③). Строит подграф связей вокруг клиента
// и ищет круговые денежные схемы (для правила R03).
package graph

import (
	"sort"
	"strings"

	"github.com/shopspring/decimal"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

const (
	minCycleLength = 3 // не считаем циклом "туда-обратно" между двумя узлами
	maxCycleDepth  = 6
)

// MoneyEdge — направленное денежное ребро from -> to, агрегированное по
// count транзакциям.
type MoneyEdge struct {
	FromNodeID string
	ToNodeID   string
	Amount     decimal.Decimal
	Count      int
}

// CycleDetector — чистый алгоритм: вход — рёбра денежного потока, выход —
// найденные циклы. Никакого доступа к БД (в отличие от Engine).
type CycleDetector struct {
	edges     []MoneyEdge
	adjacency map[string][]int // node id -> индексы исходящих рёбер
	labels    map[string]string
}

func NewCycleDetector(edges []MoneyEdge, labels map[string]string) *CycleDetector {
	adj := make(map[string][]int)
	for i, e := range edges {
		adj[e.FromNodeID] = append(adj[e.FromNodeID], i)
	}
	return &CycleDetector{edges: edges, adjacency: adj, labels: labels}
}

// FindCyclesFrom находит все простые циклы длиной [minCycleLength,
// maxCycleDepth], начинающиеся и заканчивающиеся в startNodeID.
func (d *CycleDetector) FindCyclesFrom(startNodeID string) []domain.CycleView {
	cycles, _ := d.search(startNodeID)
	return cycles
}

// FindCycleEdgesFrom возвращает множество индексов рёбер, входящих хотя
// бы в один найденный цикл.
func (d *CycleDetector) FindCycleEdgesFrom(startNodeID string) map[int]bool {
	_, edgeIdx := d.search(startNodeID)
	return edgeIdx
}

func (d *CycleDetector) search(start string) ([]domain.CycleView, map[int]bool) {
	var result []domain.CycleView
	cycleEdges := map[int]bool{}
	seenKeys := map[string]bool{}

	path := []string{start}
	var pathEdges []int
	visited := map[string]bool{start: true}

	d.dfs(start, start, &path, &pathEdges, visited, &result, seenKeys, cycleEdges)
	return result, cycleEdges
}

func (d *CycleDetector) dfs(start, current string, path *[]string, pathEdges *[]int,
	visited map[string]bool, result *[]domain.CycleView, seenKeys map[string]bool, cycleEdges map[int]bool) {

	if len(*path) > maxCycleDepth {
		return
	}

	for _, ei := range d.adjacency[current] {
		edge := d.edges[ei]
		next := edge.ToNodeID

		if next == start && len(*path) >= minCycleLength {
			*pathEdges = append(*pathEdges, ei)
			d.recordCycle(*path, *pathEdges, seenKeys, result, cycleEdges)
			*pathEdges = (*pathEdges)[:len(*pathEdges)-1]
			continue
		}
		if visited[next] {
			continue
		}

		visited[next] = true
		*path = append(*path, next)
		*pathEdges = append(*pathEdges, ei)

		d.dfs(start, next, path, pathEdges, visited, result, seenKeys, cycleEdges)

		*pathEdges = (*pathEdges)[:len(*pathEdges)-1]
		*path = (*path)[:len(*path)-1]
		delete(visited, next)
	}
}

func (d *CycleDetector) recordCycle(path []string, pathEdges []int, seenKeys map[string]bool,
	result *[]domain.CycleView, cycleEdges map[int]bool) {

	nodeIDs := append([]string(nil), path...)

	sorted := append([]string(nil), nodeIDs...)
	sort.Strings(sorted)
	key := "|" + strings.Join(sorted, "|")
	if seenKeys[key] {
		return
	}
	seenKeys[key] = true

	for _, ei := range pathEdges {
		cycleEdges[ei] = true
	}

	pathLabels := make([]string, 0, len(nodeIDs)+1)
	for _, id := range nodeIDs {
		if lbl, ok := d.labels[id]; ok {
			pathLabels = append(pathLabels, lbl)
		} else {
			pathLabels = append(pathLabels, id)
		}
	}
	pathLabels = append(pathLabels, pathLabels[0]) // замыкаем визуально

	total := decimal.Zero
	count := 0
	for _, ei := range pathEdges {
		total = total.Add(d.edges[ei].Amount)
		count += d.edges[ei].Count
	}

	*result = append(*result, domain.CycleView{
		PathLabels:       pathLabels,
		TotalAmount:      total,
		TransactionCount: count,
	})
}
