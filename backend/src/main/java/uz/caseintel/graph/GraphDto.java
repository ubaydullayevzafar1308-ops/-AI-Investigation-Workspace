package uz.caseintel.graph;

import java.math.BigDecimal;
import java.util.List;

/**
 * Подграф связей вокруг клиента (глубина 2), см. ARCHITECTURE.md §7.
 * Формат соответствует контракту GET /api/cases/{id}/graph — фронт
 * (react-force-graph-2d) рендерит nodes/edges напрямую без трансформации.
 *
 * Два вида рёбер сосуществуют независимо и НЕ дедуплицируются друг с
 * другом: money_flow (агрегат по транзакциям между парой субъектов) и
 * relation (структурная связь — director/founder/same_device/...).
 * Одна пара узлов может иметь оба ребра одновременно (пример из спеки:
 * client_123 -> company_45 и как money_flow, и как relation/director).
 */
public record GraphDto(
        List<Node> nodes,
        List<Edge> edges
) {

    public record Node(
            String id,          // "client_123" | "company_45" — префикс + БД id
            String type,        // "client" | "company"
            String label,       // отображаемое имя
            boolean flagged     // blacklisted ИЛИ участвует в сработавшем правиле
    ) {}

    public record Edge(
            String source,
            String target,
            String kind,                // "money_flow" | "relation"
            BigDecimal total,           // только для money_flow — сумма всех транзакций между парой
            Integer count,               // только для money_flow — число транзакций
            Boolean suspicious,          // только для money_flow — признак подозрительности потока
            String relationType         // только для relation — Relationship.TYPE_*
    ) {
        public static Edge moneyFlow(String source, String target, BigDecimal total, int count, boolean suspicious) {
            return new Edge(source, target, "money_flow", total, count, suspicious, null);
        }

        public static Edge relation(String source, String target, String relationType) {
            return new Edge(source, target, "relation", null, null, null, relationType);
        }
    }

    /** Строит id узла в формате "{type}_{dbId}", как в примере спеки. */
    public static String nodeId(String type, Long dbId) {
        return type + "_" + dbId;
    }
}
