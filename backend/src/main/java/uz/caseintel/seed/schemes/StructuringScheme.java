package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * Structuring — триггерит R01. См. ARCHITECTURE.md §16.
 *
 * 4 перевода чуть ниже порога обязательного контроля (R01StructuringRule.THRESHOLD)
 * за 2 дня — с запасом сверх MIN_COUNT=3, чтобы правило сработало
 * уверенно даже если конкретные суммы попадут на границу диапазона.
 */
public class StructuringScheme {

    private final SchemeSupport support;

    public StructuringScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        long clientId = support.insertClient("Rashidov Sardor Anvarovich", "device-struct-01", false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);
        long counterpartyAccount = support.insertAccount(support.companyAccountType(),
                support.insertCompany("OOO \"Counterparty Trade\"",
                        java.time.LocalDate.now().minusYears(2), null, false));

        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
        long lastTxId = 0;

        BigDecimal[] amounts = {
                new BigDecimal("96000000"), new BigDecimal("97500000"),
                new BigDecimal("95200000"), new BigDecimal("98800000")
        };

        for (int i = 0; i < amounts.length; i++) {
            lastTxId = support.insertTransaction(
                    clientAccount, counterpartyAccount, amounts[i],
                    Transaction.TYPE_TRANSFER,
                    base.plusHours(i * 14L),
                    "Оплата по договору поставки №" + (100 + i)
            );
        }

        return support.insertAlert(lastTxId, clientId, "R01_STRUCTURING_PATTERN", "high");
    }
}
