package uz.caseintel.graph;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Graph Engine (③ в пайплайне). Строит подграф связей вокруг клиента
 * глубины 2 через рекурсивный CTE (см. ARCHITECTURE.md §7) — намеренно
 * без Neo4j/выделенной графовой БД, обычный PostgreSQL справляется на
 * масштабе одного расследования (десятки-сотни узлов, не миллионы).
 *
 * Два независимых источника рёбер:
 *  - relation-рёбра — из таблицы relationships (структурные связи);
 *  - money_flow-рёбра — агрегат по transactions между парой субъектов,
 *    построенный отдельным SQL-запросом (не CTE — сумма по счетам,
 *    а не по relationships).
 * Собираются в общий GraphDto одним проходом, без дедупликации между
 * видами (см. комментарий в GraphDto).
 */
@Service
public class GraphEngineService {

    private static final int MAX_DEPTH = 2;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public GraphDto build(Long clientId) {
        List<Tuple> relationRows = fetchRelationSubgraph(clientId);
        List<Tuple> moneyFlowRows = fetchMoneyFlowEdges(clientId);

        Map<String, GraphDto.Node> nodesById = new LinkedHashMap<>();
        List<GraphDto.Edge> edges = new ArrayList<>();

        // Корневой узел — сам клиент, всегда присутствует, даже если связей нет.
        nodesById.put(GraphDto.nodeId("client", clientId),
                new GraphDto.Node(GraphDto.nodeId("client", clientId), "client", "Клиент #" + clientId, false));

        for (Tuple row : relationRows) {
            String sourceType = row.get("source_type", String.class);
            Long sourceId = row.get("source_id", Long.class);
            String targetType = row.get("target_type", String.class);
            Long targetId = row.get("target_id", Long.class);
            String relationType = row.get("relation_type", String.class);

            String sourceNodeId = GraphDto.nodeId(sourceType, sourceId);
            String targetNodeId = GraphDto.nodeId(targetType, targetId);

            nodesById.putIfAbsent(sourceNodeId, placeholderNode(sourceType, sourceId));
            nodesById.putIfAbsent(targetNodeId, placeholderNode(targetType, targetId));

            edges.add(GraphDto.Edge.relation(sourceNodeId, targetNodeId, relationType));
        }

        for (Tuple row : moneyFlowRows) {
            String sourceType = row.get("source_type", String.class);
            Long sourceId = row.get("source_id", Long.class);
            String targetType = row.get("target_type", String.class);
            Long targetId = row.get("target_id", Long.class);
            BigDecimal total = row.get("total_amount", BigDecimal.class);
            Number countNum = row.get("tx_count", Number.class);

            String sourceNodeId = GraphDto.nodeId(sourceType, sourceId);
            String targetNodeId = GraphDto.nodeId(targetType, targetId);

            nodesById.putIfAbsent(sourceNodeId, placeholderNode(sourceType, sourceId));
            nodesById.putIfAbsent(targetNodeId, placeholderNode(targetType, targetId));

            // "Подозрительно" здесь — предварительная эвристика для подсветки на фронте
            // (крупная сумма ИЛИ много операций); финальное решение о риске всегда
            // остаётся за Risk Engine (⑤) — этот флаг не влияет на Risk Score.
            boolean suspicious = total.compareTo(new BigDecimal("300000000")) >= 0
                    || countNum.intValue() >= 10;

            edges.add(GraphDto.Edge.moneyFlow(sourceNodeId, targetNodeId, total, countNum.intValue(), suspicious));
        }

        enrichNodeLabelsAndFlags(nodesById);

        return new GraphDto(List.copyOf(nodesById.values()), edges);
    }

    /**
     * Строит рёбра денежного потока и метки узлов в формате, который
     * ожидает CycleDetector. Вызывается Data Collector'ом (①) ДО Rule
     * Engine (②), чтобы R03 могло прочитать уже готовые циклы из
     * DossierDto.moneyCycles (см. комментарий в DossierDto.java —
     * R03 остаётся чистой функцией, не завися от порядка сервисов).
     *
     * В отличие от build()/fetchMoneyFlowEdges (которые строят только
     * звезду "клиент <-> counterpart" для отображения на фронте), здесь
     * агрегируются НАПРАВЛЕННЫЕ денежные потоки между ВСЕМИ парами
     * субъектов подграфа глубины 2 — иначе DFS в CycleDetector физически
     * не сможет найти цикл A->B->C->A: ему нужно ребро B->C, а не только
     * связи клиента с B и с C по отдельности.
     */
    @Transactional(readOnly = true)
    public MoneyFlowGraph buildMoneyFlowGraph(Long clientId) {
        List<Tuple> relationRows = fetchRelationSubgraph(clientId);

        // Собираем множество узлов подграфа (client + всё, до чего дотянулся CTE).
        record OwnerRef(String type, Long id) {}
        Set<OwnerRef> owners = new LinkedHashSet<>();
        owners.add(new OwnerRef("client", clientId));

        for (Tuple row : relationRows) {
            owners.add(new OwnerRef(row.get("source_type", String.class), row.get("source_id", Long.class)));
            owners.add(new OwnerRef(row.get("target_type", String.class), row.get("target_id", Long.class)));
        }

        if (owners.size() <= 1) {
            // Клиент без связей — цикл в принципе невозможен, не тратим запрос на пустой подграф.
            Map<String, String> onlyClientLabel = new LinkedHashMap<>();
            onlyClientLabel.put(GraphDto.nodeId("client", clientId), "Клиент #" + clientId);
            return new MoneyFlowGraph(List.of(), onlyClientLabel);
        }

        List<String> ownerTypesList = owners.stream().map(OwnerRef::type).toList();
        List<Long> ownerIdsList = owners.stream().map(OwnerRef::id).toList();

        List<Tuple> directedFlowRows = fetchDirectedMoneyFlowsWithinSubgraph(ownerTypesList, ownerIdsList);

        List<CycleDetector.MoneyEdge> edges = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(GraphDto.nodeId("client", clientId), "Клиент #" + clientId);

        for (Tuple row : directedFlowRows) {
            String fromType = row.get("from_owner_type", String.class);
            Long fromId = row.get("from_owner_id", Long.class);
            String toType = row.get("to_owner_type", String.class);
            Long toId = row.get("to_owner_id", Long.class);
            BigDecimal total = row.get("total_amount", BigDecimal.class);
            Number countNum = row.get("tx_count", Number.class);

            String fromNodeId = GraphDto.nodeId(fromType, fromId);
            String toNodeId = GraphDto.nodeId(toType, toId);
            labels.putIfAbsent(fromNodeId, fromNodeId);
            labels.putIfAbsent(toNodeId, toNodeId);

            edges.add(new CycleDetector.MoneyEdge(fromNodeId, toNodeId, total, countNum.intValue()));
        }

        enrichLabelsOnly(labels);

        return new MoneyFlowGraph(edges, labels);
    }

    /** Пара (рёбра, метки), готовая к передаче в конструктор CycleDetector. */
    public record MoneyFlowGraph(List<CycleDetector.MoneyEdge> edges, Map<String, String> labels) {}

    /**
     * Агрегирует НАПРАВЛЕННЫЕ денежные потоки (from -> to, отдельно от to -> from)
     * между всеми парами субъектов из переданного набора owner (type,id).
     *
     * Строим VALUES-список прямо в SQL-строке, а не биндим Java-массив как
     * параметр: биндинг String[]/Long[] в PostgreSQL varchar[]/bigint[] через
     * Hibernate native query ненадёжен без доп. проверки на реальной БД,
     * которой в этой среде разработки нет. Здесь это безопасно, потому что
     * owner_type — это всегда одна из двух наших констант ("client"/"company",
     * взятые из fetchRelationSubgraph — не пользовательский ввод), а owner_id
     * — Long. Значения не участвуют в конкатенации напрямую от пользователя,
     * инъекция невозможна.
     */
    @SuppressWarnings("unchecked")
    private List<Tuple> fetchDirectedMoneyFlowsWithinSubgraph(List<String> ownerTypes, List<Long> ownerIds) {
        StringBuilder valuesClause = new StringBuilder();
        for (int i = 0; i < ownerTypes.size(); i++) {
            String type = ownerTypes.get(i);
            if (!type.equals("client") && !type.equals("company")) {
                throw new IllegalStateException("Unexpected owner_type in subgraph: " + type);
            }
            if (i > 0) {
                valuesClause.append(", ");
            }
            valuesClause.append("('").append(type).append("', ").append(ownerIds.get(i)).append(")");
        }

        String sql = """
            WITH subgraph_owners(owner_type, owner_id) AS (
                VALUES %s
            )
            SELECT
                from_owner.owner_type AS from_owner_type,
                from_owner.owner_id AS from_owner_id,
                to_owner.owner_type AS to_owner_type,
                to_owner.owner_id AS to_owner_id,
                SUM(t.amount) AS total_amount,
                COUNT(*) AS tx_count
            FROM transactions t
            JOIN accounts fa ON fa.id = t.from_account
            JOIN accounts ta ON ta.id = t.to_account
            JOIN subgraph_owners from_owner ON from_owner.owner_type = fa.owner_type AND from_owner.owner_id = fa.owner_id
            JOIN subgraph_owners to_owner ON to_owner.owner_type = ta.owner_type AND to_owner.owner_id = ta.owner_id
            WHERE NOT (from_owner.owner_type = to_owner.owner_type AND from_owner.owner_id = to_owner.owner_id)
            GROUP BY from_owner.owner_type, from_owner.owner_id, to_owner.owner_type, to_owner.owner_id
            """.formatted(valuesClause);

        return entityManager.createNativeQuery(sql, Tuple.class).getResultList();
    }

    /** Дозаполняет читаемые метки (без flagged — CycleDetector в него не смотрит). */
    private void enrichLabelsOnly(Map<String, String> labels) {
        List<Long> clientIds = new ArrayList<>();
        List<Long> companyIds = new ArrayList<>();
        for (String nodeId : labels.keySet()) {
            if (nodeId.startsWith("client_")) {
                clientIds.add(Long.valueOf(nodeId.substring("client_".length())));
            } else if (nodeId.startsWith("company_")) {
                companyIds.add(Long.valueOf(nodeId.substring("company_".length())));
            }
        }
        if (!clientIds.isEmpty()) {
            List<Tuple> rows = entityManager.createNativeQuery(
                            "SELECT id, full_name AS label FROM clients WHERE id IN :ids", Tuple.class)
                    .setParameter("ids", clientIds)
                    .getResultList();
            for (Tuple row : rows) {
                labels.put(GraphDto.nodeId("client", ((Number) row.get("id")).longValue()), row.get("label", String.class));
            }
        }
        if (!companyIds.isEmpty()) {
            List<Tuple> rows = entityManager.createNativeQuery(
                            "SELECT id, name AS label FROM companies WHERE id IN :ids", Tuple.class)
                    .setParameter("ids", companyIds)
                    .getResultList();
            for (Tuple row : rows) {
                labels.put(GraphDto.nodeId("company", ((Number) row.get("id")).longValue()), row.get("label", String.class));
            }
        }
    }

    /**
     * Рекурсивный CTE ровно по ARCHITECTURE.md §7 — глубина 2,
     * дедупликация через DISTINCT в самом запросе.
     */
    @SuppressWarnings("unchecked")
    private List<Tuple> fetchRelationSubgraph(Long clientId) {
        String sql = """
            WITH RECURSIVE graph AS (
                SELECT source_type, source_id, target_type, target_id, relation_type, 1 AS depth
                FROM relationships
                WHERE (source_type = 'client' AND source_id = :clientId)
                   OR (target_type = 'client' AND target_id = :clientId)
              UNION
                SELECT r.source_type, r.source_id, r.target_type, r.target_id, r.relation_type, g.depth + 1
                FROM relationships r
                JOIN graph g ON (r.source_type = g.target_type AND r.source_id = g.target_id)
                WHERE g.depth < :maxDepth
            )
            SELECT DISTINCT source_type, source_id, target_type, target_id, relation_type FROM graph
            """;

        return entityManager.createNativeQuery(sql, Tuple.class)
                .setParameter("clientId", clientId)
                .setParameter("maxDepth", MAX_DEPTH)
                .getResultList();
    }

    /**
     * Агрегирует транзакции между клиентом и каждым counterpart-субъектом
     * (владельцем счёта на другом конце) в money_flow-ребро. Смотрит
     * только на прямые переводы клиента (глубина 1) — money_flow не
     * рекурсивен, это агрегат фактических денежных операций, а не
     * структурных связей.
     */
    @SuppressWarnings("unchecked")
    private List<Tuple> fetchMoneyFlowEdges(Long clientId) {
        String sql = """
            SELECT
                'client'::varchar AS source_type,
                :clientId AS source_id,
                counterpart.owner_type AS target_type,
                counterpart.owner_id AS target_id,
                SUM(t.amount) AS total_amount,
                COUNT(*) AS tx_count
            FROM transactions t
            JOIN accounts own ON own.id IN (t.from_account, t.to_account)
                             AND own.owner_type = 'client' AND own.owner_id = :clientId
            JOIN accounts counterpart ON counterpart.id = (
                CASE WHEN t.from_account = own.id THEN t.to_account ELSE t.from_account END
            )
            WHERE NOT (counterpart.owner_type = 'client' AND counterpart.owner_id = :clientId)
            GROUP BY counterpart.owner_type, counterpart.owner_id
            """;

        return entityManager.createNativeQuery(sql, Tuple.class)
                .setParameter("clientId", clientId)
                .getResultList();
    }

    private GraphDto.Node placeholderNode(String type, Long id) {
        // Метка и flagged проставляются позже в enrichNodeLabelsAndFlags —
        // на этом этапе просто резервируем узел, чтобы рёбра могли на него ссылаться.
        return new GraphDto.Node(GraphDto.nodeId(type, id), type, type + "_" + id, false);
    }

    /**
     * Дозаполняет реальные имена/флаги blacklisted для узлов, добавленных
     * как placeholder при обходе CTE. Один batched-запрос на каждый тип
     * (client/company), а не N+1 по одному узлу.
     */
    private void enrichNodeLabelsAndFlags(Map<String, GraphDto.Node> nodesById) {
        List<Long> clientIds = idsOfType(nodesById, "client");
        List<Long> companyIds = idsOfType(nodesById, "company");

        if (!clientIds.isEmpty()) {
            List<Tuple> rows = entityManager.createNativeQuery(
                            "SELECT id, full_name AS label, is_blacklisted AS flagged FROM clients WHERE id IN :ids",
                            Tuple.class)
                    .setParameter("ids", clientIds)
                    .getResultList();
            applyEnrichment(nodesById, "client", rows);
        }
        if (!companyIds.isEmpty()) {
            List<Tuple> rows = entityManager.createNativeQuery(
                            "SELECT id, name AS label, is_blacklisted AS flagged FROM companies WHERE id IN :ids",
                            Tuple.class)
                    .setParameter("ids", companyIds)
                    .getResultList();
            applyEnrichment(nodesById, "company", rows);
        }
    }

    private List<Long> idsOfType(Map<String, GraphDto.Node> nodesById, String type) {
        return nodesById.values().stream()
                .filter(n -> n.type().equals(type))
                .map(n -> Long.valueOf(n.id().substring(type.length() + 1)))
                .toList();
    }

    private void applyEnrichment(Map<String, GraphDto.Node> nodesById, String type, List<Tuple> rows) {
        for (Tuple row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String label = row.get("label", String.class);
            boolean flagged = Boolean.TRUE.equals(row.get("flagged", Boolean.class));
            String nodeId = GraphDto.nodeId(type, id);
            nodesById.put(nodeId, new GraphDto.Node(nodeId, type, label, flagged));
        }
    }
}
