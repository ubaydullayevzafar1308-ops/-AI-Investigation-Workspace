package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * Circular flow — триггерит R03. См. ARCHITECTURE.md §16.
 *
 * Клиент К -> Компания А -> Компания Б -> обратно клиенту К, замыкая
 * цикл. Это ровно тот паттерн, который находит CycleDetector.findCyclesFrom
 * (MIN_CYCLE_LENGTH=3 — здесь у нас точно 3 узла: client, companyA, companyB).
 */
public class CircularScheme {

    private final SchemeSupport support;

    public CircularScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        long clientId = support.insertClient("Yusupov Bekzod Islomovich", "device-circ-01", false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);

        long companyAId = support.insertCompany("OOO \"Zarafshon Capital\"", LocalDate.now().minusMonths(4), clientId, false);
        long companyAAccount = support.insertAccount(support.companyAccountType(), companyAId);

        long companyBId = support.insertCompany("OOO \"Registon Holding\"", LocalDate.now().minusMonths(3), null, false);
        long companyBAccount = support.insertAccount(support.companyAccountType(), companyBId);

        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(6);

        support.insertTransaction(clientAccount, companyAAccount, new BigDecimal("300000000"),
                Transaction.TYPE_TRANSFER, base, "Инвестиция в проект");
        support.insertTransaction(companyAAccount, companyBAccount, new BigDecimal("290000000"),
                Transaction.TYPE_TRANSFER, base.plusHours(20), "Оплата субподряда");
        long closingTxId = support.insertTransaction(companyBAccount, clientAccount, new BigDecimal("285000000"),
                Transaction.TYPE_TRANSFER, base.plusHours(44), "Возврат займа");

        support.insertRelationship(support.clientAccountType(), clientId, "company", companyAId,
                "director", BigDecimal.ONE);
        // ВАЖНО: buildMoneyFlowGraph (см. GraphEngineService) собирает множество
        // субъектов подграфа ТОЛЬКО через таблицу relationships (рекурсивный CTE),
        // а не через сами транзакции. Без явной связи companyB осталась бы вне
        // подграфа, и CycleDetector физически не увидел бы её как узел — цикл
        // "потерялся" бы для R03, хотя деньги реально прошли через неё. Поэтому
        // связываем все три субъекта цикла явно, а не полагаемся на то, что
        // Graph Engine сам "откроет" companyB по денежному следу.
        support.insertRelationship("company", companyAId, "company", companyBId,
                "frequent_counterparty", BigDecimal.ONE);

        return support.insertAlert(closingTxId, clientId, "R03_CIRCULAR_FLOW", "high");
    }
}
