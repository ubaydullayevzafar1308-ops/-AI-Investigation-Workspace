package uz.caseintel.seed;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import uz.caseintel.seed.generators.BackgroundDataGenerator;
import uz.caseintel.seed.generators.NameGenerator;
import uz.caseintel.seed.generators.NormalTransactionsGenerator;
import uz.caseintel.seed.schemes.CircularScheme;
import uz.caseintel.seed.schemes.FanInOutScheme;
import uz.caseintel.seed.schemes.GoldenCaseScheme;
import uz.caseintel.seed.schemes.NewCompanySpikeScheme;
import uz.caseintel.seed.schemes.SchemeSupport;
import uz.caseintel.seed.schemes.StructuringScheme;
import uz.caseintel.seed.schemes.TransitScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Точка входа генератора синтетических данных. См. ARCHITECTURE.md §16.
 *
 * Активен ТОЛЬКО в профиле seed:
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
 * или
 *   java -jar app.jar --spring.profiles.active=seed
 *
 * ВНИМАНИЕ, ПЕРЕД ЗАПУСКОМ: seed предполагает ПУСТУЮ базу (только что
 * накатанные Flyway-миграции, без данных). Повторный запуск на непустой
 * базе приведёт к конфликтам id (SeedIdAllocator начинает с 1 при
 * каждом запуске приложения) — либо очистите таблицы (TRUNCATE ...
 * CASCADE), либо поднимите БД заново перед повторным сидированием.
 */
@Configuration
@Profile("seed")
@EnableConfigurationProperties(SeedProperties.class)
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final JdbcTemplate jdbc;
    private final SeedProperties properties;

    public SeedRunner(JdbcTemplate jdbc, SeedProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        log.info("=== Seed: старт (clients={}, companies={}, transactions={}) ===",
                properties.clientCount(), properties.companyCount(), properties.backgroundTransactionCount());

        RandomGenerator random = RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(properties.randomSeed());

        var clientIds = new SeedIdAllocator();
        var companyIds = new SeedIdAllocator();
        var accountIds = new SeedIdAllocator();
        var transactionIds = new SeedIdAllocator();
        var relationshipIds = new SeedIdAllocator();
        var alertIds = new SeedIdAllocator();

        log.info("Шаг 1/3: фоновые клиенты/компании/счета...");
        var backgroundGen = new BackgroundDataGenerator(jdbc, random, clientIds, companyIds, accountIds);
        backgroundGen.generate(properties.clientCount(), properties.companyCount());

        log.info("Шаг 2/3: фоновые транзакции ({})...", properties.backgroundTransactionCount());
        // Все счета (клиентские + компаний), а не только клиентские —
        // фоновый шум должен включать переводы клиент<->компания тоже,
        // не только клиент<->клиент. population.clientAccountIds() не
        // покрывает счета компаний, поэтому забираем полный список одним
        // запросом (дешевле, чем плодить ещё один allocator/generate-метод
        // только ради объединения двух списков id).
        List<Long> allAccountIds = jdbc.query("SELECT id FROM accounts", (rs, i) -> rs.getLong("id"));
        var txGen = new NormalTransactionsGenerator(jdbc, random, transactionIds);
        txGen.generate(allAccountIds, properties.backgroundTransactionCount());

        log.info("Шаг 3/3: 6 срежиссированных схем...");
        var names = new NameGenerator(random);
        var support = new SchemeSupport(jdbc, clientIds, companyIds, accountIds, transactionIds, relationshipIds, alertIds, names);

        long structuringAlertId = new StructuringScheme(support).run();
        long circularAlertId = new CircularScheme(support).run();
        long transitAlertId = new TransitScheme(support).run();
        long spikeAlertId = new NewCompanySpikeScheme(support).run();
        long fanInOutAlertId = new FanInOutScheme(support).run();
        long goldenCaseAlertId = new GoldenCaseScheme(support).run();

        fixSequencesAfterSeed();

        long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000;
        log.info("=== Seed: готово за {}с ===", elapsedSeconds);
        log.info("Alert id для демо: structuring={}, circular={}, transit={}, newCompanySpike={}, fanInOut={}, GOLDEN_CASE={}",
                structuringAlertId, circularAlertId, transitAlertId, spikeAlertId, fanInOutAlertId, goldenCaseAlertId);
        log.info("Запусти POST /api/alerts/{}/investigate чтобы собрать golden case для демо.", goldenCaseAlertId);
    }

    /**
     * После batch-инсертов с явно указанными id последовательности
     * (BIGSERIAL) в Postgres НЕ сдвинуты — они всё ещё думают, что
     * следующий id = 1. Без этого шага первый же обычный INSERT через
     * приложение (например создание Case через API) упадёт с конфликтом
     * первичного ключа. setval сдвигает sequence на текущий max(id)+1.
     */
    private void fixSequencesAfterSeed() {
        String[] tables = {
                "clients", "companies", "accounts", "transactions",
                "relationships", "alerts", "cases", "rule_hits", "reports", "audit_log"
        };
        for (String table : tables) {
            jdbc.execute("""
                SELECT setval(pg_get_serial_sequence('%s', 'id'), COALESCE((SELECT MAX(id) FROM %s), 1))
                """.formatted(table, table));
        }
    }
}
