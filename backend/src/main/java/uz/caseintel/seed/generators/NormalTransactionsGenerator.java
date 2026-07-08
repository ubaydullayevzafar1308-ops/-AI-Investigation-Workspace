package uz.caseintel.seed.generators;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import uz.caseintel.entity.Transaction;
import uz.caseintel.seed.SeedIdAllocator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Фоновые ("нормальные") транзакции между случайными счетами — шум,
 * на котором Rule Engine НЕ должен срабатывать. См. ARCHITECTURE.md
 * §16: ~100 000 фоновых транзакций.
 *
 * Суммы и распределение по типам намеренно НЕ похожи на паттерны
 * правил R01-R10 (не близко к порогу структуринга, не транзитные пары
 * в узком окне и т.п.) — иначе фоновый шум сам создавал бы ложные
 * срабатывания, что смазало бы демонстрацию 6 срежиссированных схем.
 */
public class NormalTransactionsGenerator {

    private static final int BATCH_SIZE = 2000;
    private static final String[] TX_TYPES = {
            Transaction.TYPE_TRANSFER, Transaction.TYPE_CARD_PAYMENT,
            Transaction.TYPE_CASH_IN, Transaction.TYPE_CASH_OUT
    };

    private final JdbcTemplate jdbc;
    private final RandomGenerator random;
    private final SeedIdAllocator transactionIds;

    public NormalTransactionsGenerator(JdbcTemplate jdbc, RandomGenerator random, SeedIdAllocator transactionIds) {
        this.jdbc = jdbc;
        this.random = random;
        this.transactionIds = transactionIds;
    }

    public void generate(List<Long> allAccountIds, int count) {
        if (allAccountIds.size() < 2) {
            return;
        }

        String sql = """
            INSERT INTO transactions (id, from_account, to_account, amount, currency, tx_type, description, tx_timestamp, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        for (int i = 0; i < count; i++) {
            long id = transactionIds.next();
            String txType = TX_TYPES[random.nextInt(TX_TYPES.length)];

            Long fromAccount = allAccountIds.get(random.nextInt(allAccountIds.size()));
            Long toAccount = pickDifferentAccount(allAccountIds, fromAccount);

            BigDecimal amount = randomAmount();

            batch.add(new Object[]{
                    id,
                    fromAccount,
                    toAccount,
                    amount,
                    "UZS",
                    txType,
                    "Обычная операция",
                    randomPastTimestamp(),
                    OffsetDateTime.now(ZoneOffset.UTC)
            });

            if (batch.size() == BATCH_SIZE || i == count - 1) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
    }

    private Long pickDifferentAccount(List<Long> accountIds, Long exclude) {
        Long candidate;
        int attempts = 0;
        do {
            candidate = accountIds.get(random.nextInt(accountIds.size()));
            attempts++;
        } while (candidate.equals(exclude) && attempts < 5);
        return candidate;
    }

    private BigDecimal randomAmount() {
        double value = 50_000 + random.nextDouble() * 14_950_000;
        return BigDecimal.valueOf(Math.round(value));
    }

    private OffsetDateTime randomPastTimestamp() {
        long daysAgo = random.nextInt(365);
        int hour = 6 + random.nextInt(16);
        return OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(daysAgo)
                .withHour(hour)
                .withMinute(random.nextInt(60))
                .withSecond(0)
                .withNano(0);
    }
}
