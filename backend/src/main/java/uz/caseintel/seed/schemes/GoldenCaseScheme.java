package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * Golden Case — главный демо-кейс. См. ARCHITECTURE.md §16.
 *
 * Клиент + 3 подставные фирмы + общий директор + общее устройство с
 * blacklisted-клиентом + предыдущий алерт. Задуман так, чтобы сработали
 * одновременно:
 *  - R01 Structuring (20)  — 4 near-threshold перевода
 *  - R03 Circular flow (25) — К -> С-1 -> С-2 -> К
 *  - R07 Shared attributes (20) — общее устройство с blacklisted-клиентом
 *  - Repeat offender bonus (+10) — прошлый алерт
 *  - R04 New entity spike (15) — третья подставная фирма, новая, с резким оборотом
 * Итого ожидаемый score: 20+25+20+15+10=90, что близко к заявленным ~91
 * из спеки (точное число будет видно только при реальном прогоне на
 * живой БД — здесь мы намеренно целимся в high-диапазон, а не в
 * конкретное значение с точностью до единицы; см. предупреждение внизу
 * файла про то, что это не проверено вживую).
 *
 * Красивый граф: клиент в центре, 3 компании вокруг, одна из них
 * связана с blacklisted-клиентом через общее устройство.
 */
public class GoldenCaseScheme {

    private final SchemeSupport support;

    public GoldenCaseScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        String sharedDeviceId = "device-golden-shared-001";

        long blacklistedClientId = support.insertClient("Xolmatov Jasur Anvarovich", sharedDeviceId, true);

        long clientId = support.insertClient("Karimov Alisher Botirovich", sharedDeviceId, false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);

        long company1Id = support.insertCompany("OOO \"Barakat Trade\"", LocalDate.now().minusMonths(5), clientId, false);
        long company1Account = support.insertAccount(support.companyAccountType(), company1Id);

        long company2Id = support.insertCompany("OOO \"Vega Import\"", LocalDate.now().minusMonths(4), null, false);
        long company2Account = support.insertAccount(support.companyAccountType(), company2Id);

        long company3Id = support.insertCompany("OOO \"Ipak Yoli Trading\"", LocalDate.now().minusDays(45), clientId, false);
        long company3Account = support.insertAccount(support.companyAccountType(), company3Id);

        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(14);

        BigDecimal[] structuringAmounts = {
                new BigDecimal("96500000"), new BigDecimal("98200000"),
                new BigDecimal("95800000"), new BigDecimal("97100000")
        };
        for (int i = 0; i < structuringAmounts.length; i++) {
            support.insertTransaction(clientAccount, company3Account, structuringAmounts[i],
                    Transaction.TYPE_TRANSFER, base.plusHours(i * 14L), "Оплата поставки, транш " + (i + 1));
        }

        var cycleStart = base.plusDays(5);
        support.insertTransaction(clientAccount, company1Account, new BigDecimal("400000000"),
                Transaction.TYPE_TRANSFER, cycleStart, "Инвестиция в развитие");
        support.insertTransaction(company1Account, company2Account, new BigDecimal("390000000"),
                Transaction.TYPE_TRANSFER, cycleStart.plusHours(18), "Оплата услуг");
        long cycleClosingTxId = support.insertTransaction(company2Account, clientAccount, new BigDecimal("385000000"),
                Transaction.TYPE_TRANSFER, cycleStart.plusHours(40), "Возврат займа учредителю");

        support.insertRelationship(support.clientAccountType(), clientId, "company", company1Id,
                "director", BigDecimal.ONE);
        support.insertRelationship("company", company1Id, "company", company2Id,
                "frequent_counterparty", BigDecimal.ONE);
        support.insertRelationship(support.clientAccountType(), clientId, "company", company3Id,
                "director", BigDecimal.ONE);
        support.insertRelationship(support.clientAccountType(), clientId,
                support.clientAccountType(), blacklistedClientId, "same_device", BigDecimal.ONE);

        long pastTxId = support.insertTransaction(clientAccount, company1Account, new BigDecimal("50000000"),
                Transaction.TYPE_TRANSFER, base.minusMonths(4), "Прошлая операция");
        support.insertAlert(pastTxId, clientId, "PAST_SUSPICIOUS_TRANSFER", "medium");

        return support.insertAlert(cycleClosingTxId, clientId, "GOLDEN_CASE_DEMO", "high");
    }
}
