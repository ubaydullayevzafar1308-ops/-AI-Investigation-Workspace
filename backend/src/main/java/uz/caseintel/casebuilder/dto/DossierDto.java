package uz.caseintel.casebuilder.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Полное досье клиента, собранное Data Collector (① в пайплайне) для
 * одного расследования. Это единственный вход для Rule Engine (②) —
 * каждое правило (R01..R10) читает из DossierDto и ничего не запрашивает
 * из репозиториев напрямую, чтобы:
 *  а) правила были чистыми функциями (DossierDto -> Optional<RuleResult>),
 *     легко тестируемыми без БД;
 *  б) весь пайплайн работал с одним согласованным снимком данных
 *     (нет риска, что разные правила увидят разное состояние БД).
 *
 * Graph Engine (③) работает отдельно, напрямую через рекурсивный CTE
 * (см. ARCHITECTURE.md §7) — в DossierDto попадает только "плоский"
 * список связей глубины 1 (relations), которого достаточно для R07
 * (Shared attributes) и R03 (для затравки CycleDetector).
 */
public record DossierDto(
        Long clientId,
        String fullName,
        String inn,
        String phone,
        String deviceId,
        String address,
        LocalDate registrationDate,
        String clientType,
        boolean blacklisted,

        List<Long> accountIds,

        /** Все транзакции по счетам клиента (входящие и исходящие), отсортированные по времени. */
        List<TxView> transactions,

        /** Связи глубины 1 вокруг клиента (founder/director/same_address/same_device/...). */
        List<RelationView> relations,

        /** Компании, где клиент — директор/учредитель (нужно для R04: New entity spike). */
        List<CompanyView> relatedCompanies,

        /** Прошлые алерты этого клиента — основа "repeat offender" усилителя в Risk Engine. */
        List<PastAlertView> pastAlerts,

        /**
         * Круговые денежные схемы (A -> B -> C -> A), найденные CycleDetector'ом
         * ДО запуска Rule Engine. Технически цикл — это уже результат Graph
         * Engine (③), который в общей схеме пайплайна идёт ПОСЛЕ Rule Engine (②);
         * но R03 (Circular flow) логически принадлежит списку правил, поэтому
         * Data Collector (①) прогоняет CycleDetector заранее и кладёт готовый
         * результат сюда — R03 остаётся чистой функцией DossierDto -> Optional,
         * как и все остальные правила, не завися от порядка вызова сервисов.
         * Пусто, если циклов не найдено.
         */
        List<CycleView> moneyCycles
) {

    /**
     * Одна транзакция с точки зрения клиента: direction показывает,
     * был ли счёт клиента источником или получателем — правила часто
     * это разграничивают (например R09 Fan-in/Fan-out).
     */
    public record TxView(
            Long transactionId,
            Long fromAccountId,
            Long toAccountId,
            String direction,          // "in" | "out" — относительно счетов клиента
            BigDecimal amount,
            String currency,
            String txType,             // Transaction.TYPE_*
            OffsetDateTime timestamp
    ) {
        public static final String DIRECTION_IN = "in";
        public static final String DIRECTION_OUT = "out";
    }

    public record RelationView(
            String counterpartType,    // "client" | "company"
            Long counterpartId,
            String counterpartLabel,   // отображаемое имя (для explanation-текстов, ещё не псевдонимизировано)
            String relationType,       // Relationship.TYPE_*
            boolean counterpartBlacklisted
    ) {}

    public record CompanyView(
            Long companyId,
            String name,
            LocalDate registrationDate,
            boolean blacklisted,
            String roleOfClient,       // "director" | "founder"
            /**
             * Оборот по счетам компании за весь период, доступный в досье
             * (сумма amount по транзакциям, где participant — счёт этой
             * компании). Data Collector считает это агрегатом при сборке —
             * правилу R04 не нужно видеть сырые транзакции компании,
             * только уже посчитанный итог.
             */
            BigDecimal turnoverAmount
    ) {}

    public record PastAlertView(
            Long alertId,
            Long caseId,               // null, если по алерту кейс ещё не заводился
            String status,             // Case.STATUS_* если caseId не null
            OffsetDateTime createdAt
    ) {}

    /**
     * Один найденный цикл денежного потока: путь по субъектам
     * ("К-1 → OOO Barakat → OOO Vega → К-1") и суммарный оборот по циклу.
     * pathLabels — отображаемые метки узлов в порядке обхода (замкнутый
     * путь: последний элемент логически равен первому).
     */
    public record CycleView(
            List<String> pathLabels,
            BigDecimal totalAmount,
            int transactionCount
    ) {}
}
