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
        // Раньше это было ОБЯЗАТЕЛЬНЫМ workaround'ом: buildMoneyFlowGraph
        // (GraphEngineService) собирал подграф только через relationships,
        // и без этой связи companyB была бы невидима для CycleDetector даже
        // при наличии реального денежного перевода через неё. Это исправлено
        // (GraphEngineService теперь находит соседей и через transactions),
        // так что технически эта связь больше не обязательна для того, чтобы
        // R03 сработал — оставляем её как реалистичную деталь данных
        // (директор часто действительно знаком с контрагентами своей фирмы).
        support.insertRelationship("company", companyAId, "company", companyBId,
                "frequent_counterparty", BigDecimal.ONE);

        return support.insertAlert(closingTxId, clientId, "R03_CIRCULAR_FLOW", "high");
    }
}
