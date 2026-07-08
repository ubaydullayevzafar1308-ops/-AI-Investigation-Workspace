package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * Rapid movement / transit — триггерит R02. См. ARCHITECTURE.md §16.
 *
 * Крупная сумма приходит клиенту и почти сразу (в пределах MAX_GAP=6ч)
 * уходит дальше почти той же суммой (в пределах ±10% по
 * R02RapidMovementRule.AMOUNT_TOLERANCE) — классический транзитный счёт.
 */
public class TransitScheme {

    private final SchemeSupport support;

    public TransitScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        long clientId = support.insertClient("Nazarov Otabek Rustamovich", "device-transit-01", false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);

        long sourceCompanyId = support.insertCompany("OOO \"Aral Trans\"", LocalDate.now().minusYears(1), null, false);
        long sourceAccount = support.insertAccount(support.companyAccountType(), sourceCompanyId);

        long destinationCompanyId = support.insertCompany("OOO \"Xorazm Trans\"", LocalDate.now().minusMonths(8), null, false);
        long destinationAccount = support.insertAccount(support.companyAccountType(), destinationCompanyId);

        var incomingTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);

        support.insertTransaction(sourceAccount, clientAccount, new BigDecimal("180000000"),
                Transaction.TYPE_TRANSFER, incomingTime, "Оплата консультационных услуг");
        long outgoingTxId = support.insertTransaction(clientAccount, destinationAccount, new BigDecimal("176000000"),
                Transaction.TYPE_TRANSFER, incomingTime.plusHours(3), "Возврат по договору");

        return support.insertAlert(outgoingTxId, clientId, "R02_RAPID_MOVEMENT", "medium");
    }
}
