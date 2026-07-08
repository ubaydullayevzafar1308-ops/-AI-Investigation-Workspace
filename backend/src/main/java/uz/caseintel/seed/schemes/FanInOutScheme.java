package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * Fan-in / Fan-out — триггерит R09. См. ARCHITECTURE.md §16.
 *
 * 5 мелких входящих (>= MIN_FAN_IN_COUNT=4) в пределах FAN_IN_WINDOW=2
 * дня, затем один крупный исходящий на сумму, близкую к их сумме
 * (в пределах ±15% по R09FanInFanOutRule.FAN_IN_TOLERANCE).
 */
public class FanInOutScheme {

    private final SchemeSupport support;

    public FanInOutScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        long clientId = support.insertClient("Turdiev Islom Davronovich", "device-fanio-01", false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);

        long collectorCompanyId = support.insertCompany("OOO \"Yashnobod Market\"",
                LocalDate.now().minusYears(1), null, false);
        long collectorAccount = support.insertAccount(support.companyAccountType(), collectorCompanyId);

        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        BigDecimal[] inbound = {
                new BigDecimal("18000000"), new BigDecimal("22000000"), new BigDecimal("19500000"),
                new BigDecimal("21000000"), new BigDecimal("20500000")
        };

        for (int i = 0; i < inbound.length; i++) {
            support.insertTransaction(collectorAccount, clientAccount, inbound[i],
                    Transaction.TYPE_TRANSFER, base.plusHours(i * 8L), "Возврат долга");
        }

        BigDecimal total = new BigDecimal("101000000");
        long outgoingTxId = support.insertTransaction(clientAccount, collectorAccount, total,
                Transaction.TYPE_TRANSFER, base.plusHours(48), "Единый перевод по итогу расчётов");

        return support.insertAlert(outgoingTxId, clientId, "R09_FAN_IN_OUT", "medium");
    }
}
