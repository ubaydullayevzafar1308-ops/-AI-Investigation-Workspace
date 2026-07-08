package uz.caseintel.seed.generators;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import uz.caseintel.seed.SeedIdAllocator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Фоновые клиенты/компании/счета — обычный "шум" датасета, на котором
 * правила НЕ должны срабатывать (легитимная активность). См.
 * ARCHITECTURE.md §16: ~5000 клиентов, ~500 компаний.
 *
 * Использует JdbcTemplate.batchUpdate вместо JpaRepository.save() в
 * цикле — на 5000+500+десятки тысяч счетов построчный save() означает
 * тысячи отдельных round-trip'ов в БД, что на таком объёме неприемлемо
 * медленно. Batch insert группирует по BATCH_SIZE строк на один
 * round-trip. Id выделяются вручную через SeedIdAllocator (см. его
 * javadoc — почему это нужно и что сделать после сидирования).
 */
public class BackgroundDataGenerator {

    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;
    private final RandomGenerator random;
    private final NameGenerator names;
    private final SeedIdAllocator clientIds;
    private final SeedIdAllocator companyIds;
    private final SeedIdAllocator accountIds;

    public BackgroundDataGenerator(JdbcTemplate jdbc, RandomGenerator random,
                                    SeedIdAllocator clientIds, SeedIdAllocator companyIds,
                                    SeedIdAllocator accountIds) {
        this.jdbc = jdbc;
        this.random = random;
        this.names = new NameGenerator(random);
        this.clientIds = clientIds;
        this.companyIds = companyIds;
        this.accountIds = accountIds;
    }

    /** Результат генерации — id, нужные другим генераторам (транзакции, схемы). */
    public record GeneratedPopulation(List<Long> clientIds, List<Long> companyIds, List<Long> clientAccountIds) {}

    public GeneratedPopulation generate(int clientCount, int companyCount) {
        List<Long> generatedClientIds = insertClients(clientCount);
        List<Long> generatedCompanyIds = insertCompanies(companyCount, generatedClientIds);
        List<Long> generatedClientAccountIds = insertAccounts(generatedClientIds, "client");
        insertAccounts(generatedCompanyIds, "company");

        return new GeneratedPopulation(generatedClientIds, generatedCompanyIds, generatedClientAccountIds);
    }

    private List<Long> insertClients(int count) {
        List<Long> ids = new ArrayList<>(count);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        String sql = """
            INSERT INTO clients
                (id, full_name, birth_date, inn, phone, device_id, address,
                 registration_date, client_type, risk_level, is_blacklisted, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        for (int i = 0; i < count; i++) {
            long id = clientIds.next();
            ids.add(id);
            boolean male = random.nextBoolean();
            LocalDate registeredAt = randomPastDate(365 * 6);

            batch.add(new Object[]{
                    id,
                    names.fullName(male),
                    randomBirthDate(),
                    names.inn(id),
                    names.phone(),
                    names.deviceId(id),
                    names.address(),
                    registeredAt,
                    "individual",
                    "low",
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC)
            });

            if (batch.size() == BATCH_SIZE || i == count - 1) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        return ids;
    }

    private List<Long> insertCompanies(int count, List<Long> possibleDirectorClientIds) {
        List<Long> ids = new ArrayList<>(count);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        String sql = """
            INSERT INTO companies
                (id, name, inn, address, registration_date, director_id, status, is_blacklisted, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        for (int i = 0; i < count; i++) {
            long id = companyIds.next();
            ids.add(id);
            Long directorId = possibleDirectorClientIds.isEmpty()
                    ? null
                    : possibleDirectorClientIds.get(random.nextInt(possibleDirectorClientIds.size()));

            batch.add(new Object[]{
                    id,
                    names.companyName(id),
                    names.inn(1_000_000L + id),
                    names.address(),
                    randomPastDate(365 * 8),
                    directorId,
                    "active",
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC)
            });

            if (batch.size() == BATCH_SIZE || i == count - 1) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        return ids;
    }

    /** Один счёт на владельца — достаточно для фонового шума (схемы могут добавить клиентам доп. счета отдельно). */
    private List<Long> insertAccounts(List<Long> ownerIds, String ownerType) {
        List<Long> generatedIds = new ArrayList<>(ownerIds.size());
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        String sql = """
            INSERT INTO accounts (id, owner_type, owner_id, account_number, currency, opened_at, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        for (int i = 0; i < ownerIds.size(); i++) {
            long id = accountIds.next();
            generatedIds.add(id);

            batch.add(new Object[]{
                    id,
                    ownerType,
                    ownerIds.get(i),
                    accountNumber(id),
                    "UZS",
                    randomPastDate(300),
                    "active",
                    OffsetDateTime.now(ZoneOffset.UTC)
            });

            if (batch.size() == BATCH_SIZE || i == ownerIds.size() - 1) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        return generatedIds;
    }

    private String accountNumber(long id) {
        return "2020%022d".formatted(id);
    }

    private LocalDate randomBirthDate() {
        return LocalDate.of(1965 + random.nextInt(40), 1 + random.nextInt(12), 1 + random.nextInt(28));
    }

    private LocalDate randomPastDate(int maxDaysAgo) {
        return LocalDate.now().minusDays(random.nextInt(maxDaysAgo) + 1);
    }
}
