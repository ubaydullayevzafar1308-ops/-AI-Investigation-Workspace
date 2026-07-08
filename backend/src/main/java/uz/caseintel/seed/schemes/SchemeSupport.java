package uz.caseintel.seed.schemes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import uz.caseintel.entity.Account;
import uz.caseintel.seed.SeedIdAllocator;
import uz.caseintel.seed.generators.NameGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Общие вставки, переиспользуемые всеми 6 срежиссированными схемами
 * (см. ARCHITECTURE.md §16) — один клиент/компания/счёт/транзакция/связь
 * за раз, а не batch (схем немного, скорость вставки здесь не критична,
 * зато читаемость каждой схемы важнее).
 */
public class SchemeSupport {

    private final JdbcTemplate jdbc;
    private final SeedIdAllocator clientIds;
    private final SeedIdAllocator companyIds;
    private final SeedIdAllocator accountIds;
    private final SeedIdAllocator transactionIds;
    private final SeedIdAllocator relationshipIds;
    private final SeedIdAllocator alertIds;
    private final NameGenerator names;

    public SchemeSupport(JdbcTemplate jdbc, SeedIdAllocator clientIds, SeedIdAllocator companyIds,
                          SeedIdAllocator accountIds, SeedIdAllocator transactionIds,
                          SeedIdAllocator relationshipIds, SeedIdAllocator alertIds, NameGenerator names) {
        this.jdbc = jdbc;
        this.clientIds = clientIds;
        this.companyIds = companyIds;
        this.accountIds = accountIds;
        this.transactionIds = transactionIds;
        this.relationshipIds = relationshipIds;
        this.alertIds = alertIds;
        this.names = names;
    }

    public long insertClient(String fullName, String deviceId, boolean blacklisted) {
        long id = clientIds.next();
        jdbc.update("""
            INSERT INTO clients (id, full_name, birth_date, inn, phone, device_id, address,
                                  registration_date, client_type, risk_level, is_blacklisted, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, fullName, LocalDate.of(1985, 5, 15), names.inn(id), names.phone(),
                deviceId, names.address(), LocalDate.now().minusYears(3), "individual",
                blacklisted ? "high" : "low", blacklisted, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public long insertCompany(String name, LocalDate registrationDate, Long directorId, boolean blacklisted) {
        long id = companyIds.next();
        jdbc.update("""
            INSERT INTO companies (id, name, inn, address, registration_date, director_id, status, is_blacklisted, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, name, names.inn(2_000_000L + id), names.address(), registrationDate,
                directorId, "active", blacklisted, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public long insertAccount(String ownerType, long ownerId) {
        long id = accountIds.next();
        jdbc.update("""
            INSERT INTO accounts (id, owner_type, owner_id, account_number, currency, opened_at, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, ownerType, ownerId, "3030%022d".formatted(id), "UZS",
                LocalDate.now().minusMonths(6), "active", OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public long insertTransaction(long fromAccount, long toAccount, BigDecimal amount, String txType,
                                   OffsetDateTime timestamp, String description) {
        long id = transactionIds.next();
        jdbc.update("""
            INSERT INTO transactions (id, from_account, to_account, amount, currency, tx_type, description, tx_timestamp, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, fromAccount, toAccount, amount, "UZS", txType, description, timestamp,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public long insertRelationship(String sourceType, long sourceId, String targetType, long targetId,
                                    String relationType, BigDecimal confidence) {
        long id = relationshipIds.next();
        jdbc.update("""
            INSERT INTO relationships (id, source_type, source_id, target_type, target_id, relation_type, confidence, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, sourceType, sourceId, targetType, targetId, relationType, confidence,
                OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public long insertAlert(long transactionId, long clientId, String triggerReason, String severity) {
        long id = alertIds.next();
        jdbc.update("""
            INSERT INTO alerts (id, transaction_id, client_id, trigger_reason, severity, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
                id, transactionId, clientId, triggerReason, severity, "new", OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public String clientAccountType() {
        return Account.OWNER_CLIENT;
    }

    public String companyAccountType() {
        return Account.OWNER_COMPANY;
    }
}
