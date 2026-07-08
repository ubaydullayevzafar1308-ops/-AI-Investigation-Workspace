package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Transaction;

/**
 * New entity spike — триггерит R04. См. ARCHITECTURE.md §16.
 *
 * Компания зарегистрирована 30 дней назад (< MAX_AGE_DAYS=90) и уже
 * имеет оборот выше TURNOVER_THRESHOLD=200M — R04NewEntitySpikeRule
 * читает turnoverAmount как агрегат по её счетам (CompanyView), поэтому
 * здесь достаточно провести НЕСКОЛЬКО операций суммарно выше порога.
 */
public class NewCompanySpikeScheme {

    private final SchemeSupport support;

    public NewCompanySpikeScheme(SchemeSupport support) {
        this.support = support;
    }

    public long run() {
        long clientId = support.insertClient("Ergashev Farrux Jasurovich", "device-spike-01", false);
        long clientAccount = support.insertAccount(support.clientAccountType(), clientId);

        long newCompanyId = support.insertCompany("OOO \"Fargona Agro\"", LocalDate.now().minusDays(30), clientId, false);
        long newCompanyAccount = support.insertAccount(support.companyAccountType(), newCompanyId);

        var base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(5);

        support.insertTransaction(clientAccount, newCompanyAccount, new BigDecimal("120000000"),
                Transaction.TYPE_TRANSFER, base, "Оплата поставки оборудования");
        long lastTxId = support.insertTransaction(clientAccount, newCompanyAccount, new BigDecimal("110000000"),
                Transaction.TYPE_TRANSFER, base.plusDays(1), "Оплата поставки оборудования, часть 2");

        support.insertRelationship(support.clientAccountType(), clientId, "company", newCompanyId,
                "director", BigDecimal.ONE);

        return support.insertAlert(lastTxId, clientId, "R04_NEW_ENTITY_SPIKE", "medium");
    }
}
