package uz.caseintel.graph;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;

/**
 * Поиск круговых денежных схем (A → B → C → A) для правила R03.
 *
 * Чистый алгоритм: вход — список рёбер денежного потока (кто кому и
 * сколько переводил), выход — список найденных циклов. Никакого
 * доступа к БД внутри — граф передаётся уже собранным, что делает
 * класс тривиально тестируемым (в отличие от GraphEngineService,
 * которому для работы нужна реальная Postgres).
 *
 * Алгоритм: DFS с отслеживанием текущего пути; цикл найден, когда DFS
 * возвращается в стартовый узел по пути длиной >= MIN_CYCLE_LENGTH.
 * Глубина ограничена MAX_DEPTH, чтобы не уйти в комбинаторный взрыв на
 * плотных графах — для целей R03 интересны короткие, "плотные" циклы
 * (2-5 участников), а не произвольной длины обходы всего графа.
 */
public class CycleDetector {

    /** Не считаем циклом просто "туда-обратно" между двумя узлами — нужно хотя бы 3 участника. */
    private static final int MIN_CYCLE_LENGTH = 3;
    private static final int MAX_DEPTH = 6;

    /**
     * Одно направленное денежное ребро: from -> to на сумму amount,
     * агрегированную по count транзакциям (для итогового CycleView).
     */
    public record MoneyEdge(String fromNodeId, String toNodeId, BigDecimal amount, int count) {}

    private final Map<String, List<MoneyEdge>> adjacency;
    private final Map<String, String> labels;

    public CycleDetector(List<MoneyEdge> edges, Map<String, String> nodeLabels) {
        this.adjacency = new HashMap<>();
        for (MoneyEdge edge : edges) {
            adjacency.computeIfAbsent(edge.fromNodeId(), k -> new ArrayList<>()).add(edge);
        }
        this.labels = nodeLabels;
    }

    /**
     * Находит все простые циклы длины >= MIN_CYCLE_LENGTH и <= MAX_DEPTH,
     * начинающиеся и заканчивающиеся в startNodeId (обычно — сам клиент,
     * вокруг которого построен подграф). Каждый уникальный цикл (по
     * набору рёбер) возвращается один раз.
     */
    public List<CycleView> findCyclesFrom(String startNodeId) {
        return search(startNodeId).cycles();
    }

    /**
     * Рёбра, входящие хотя бы в один найденный цикл, — GraphEngineService
     * помечает их suspicious=true, чтобы фронт подсветил круговую схему.
     */
    public Set<MoneyEdge> findCycleEdgesFrom(String startNodeId) {
        return search(startNodeId).cycleEdges();
    }

    private record SearchResult(List<CycleView> cycles, Set<MoneyEdge> cycleEdges) {}

    private SearchResult search(String startNodeId) {
        List<CycleView> result = new ArrayList<>();
        Set<MoneyEdge> cycleEdges = new HashSet<>();
        Set<String> seenCycleKeys = new HashSet<>();

        Deque<String> path = new ArrayDeque<>();
        Deque<MoneyEdge> pathEdges = new ArrayDeque<>();
        path.addLast(startNodeId);

        dfs(startNodeId, startNodeId, path, pathEdges, new HashSet<>(List.of(startNodeId)), result, seenCycleKeys, cycleEdges);

        return new SearchResult(result, cycleEdges);
    }

    private void dfs(String start, String current, Deque<String> path, Deque<MoneyEdge> pathEdges,
                      Set<String> visited, List<CycleView> result, Set<String> seenCycleKeys,
                      Set<MoneyEdge> cycleEdges) {
        if (path.size() > MAX_DEPTH) {
            return;
        }

        for (MoneyEdge edge : adjacency.getOrDefault(current, List.of())) {
            String next = edge.toNodeId();

            if (next.equals(start) && path.size() >= MIN_CYCLE_LENGTH) {
                pathEdges.addLast(edge);
                recordCycle(path, pathEdges, seenCycleKeys, result, cycleEdges);
                pathEdges.removeLast();
                continue;
            }
            if (visited.contains(next)) {
                continue; // не заходим повторно в уже посещённый узел (ищем простые циклы)
            }

            visited.add(next);
            path.addLast(next);
            pathEdges.addLast(edge);

            dfs(start, next, path, pathEdges, visited, result, seenCycleKeys, cycleEdges);

            pathEdges.removeLast();
            path.removeLast();
            visited.remove(next);
        }
    }

    private void recordCycle(Deque<String> path, Deque<MoneyEdge> pathEdges,
                              Set<String> seenCycleKeys, List<CycleView> result,
                              Set<MoneyEdge> cycleEdges) {
        List<String> nodeIds = new ArrayList<>(path);

        // Ключ цикла — отсортированный набор узлов, чтобы не считать
        // один и тот же цикл дважды из-за разной точки старта обхода.
        String cycleKey = nodeIds.stream().sorted().reduce("", (a, b) -> a + "|" + b);
        if (!seenCycleKeys.add(cycleKey)) {
            return;
        }
        cycleEdges.addAll(pathEdges);

        List<String> pathLabels = new ArrayList<>();
        for (String nodeId : nodeIds) {
            pathLabels.add(labels.getOrDefault(nodeId, nodeId));
        }
        pathLabels.add(pathLabels.get(0)); // замыкаем визуально: A -> B -> C -> A

        // Оборот всего цикла — сумма ПО ВСЕМ рёбрам пути (A->B, B->C, C->A),
        // не только по замыкающему ребру. Именно это и означает "оборот 850
        // млн UZS" в UI: сколько всего денег прошло через всю схему.
        BigDecimal total = pathEdges.stream().map(MoneyEdge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int count = pathEdges.stream().mapToInt(MoneyEdge::count).sum();

        result.add(new CycleView(List.copyOf(pathLabels), total, count));
    }
}
