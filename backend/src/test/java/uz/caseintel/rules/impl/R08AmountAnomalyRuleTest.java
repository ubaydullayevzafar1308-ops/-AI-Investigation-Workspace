package uz.caseintel.rules.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.entity.Transaction;
import org.junit.jupiter.api.Test;

class R08AmountAnomalyRuleTest {

    private final R08AmountAnomalyRule rule = new R08AmountAnomalyRule();

    @Test
    void triggers_whenLatestAmountIsFiveTimesMedian() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        List<TxView> txs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            txs.add(tx(i + 1, "1000000", base.plusDays(i)));
        }
        txs.add(tx(99, "6000000", base.plusDays(10)));

        var result = rule.check(dossierWithTransactions(txs));

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R08");
        assertThat(result.get().evidence()).containsEntry("transaction_id", 99L);
    }

    @Test
    void doesNotTrigger_whenLatestAmountIsOnlySlightlyHigher() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        List<TxView> txs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            txs.add(tx(i + 1, "1000000", base.plusDays(i)));
        }
        txs.add(tx(99, "2000000", base.plusDays(10)));

        assertThat(rule.check(dossierWithTransactions(txs))).isEmpty();
    }

    @Test
    void doesNotTrigger_whenNotEnoughHistory() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        List<TxView> txs = List.of(
                tx(1, "1000000", base),
                tx(2, "1000000", base.plusDays(1)),
                tx(99, "50000000", base.plusDays(2))
        );

        assertThat(rule.check(dossierWithTransactions(txs))).isEmpty();
    }

    @Test
    void medianIsRobustToOnePriorOutlier() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        List<TxView> txs = new ArrayList<>(List.of(
                tx(1, "1000000", base),
                tx(2, "1000000", base.plusDays(1)),
                tx(3, "1000000", base.plusDays(2)),
                tx(4, "1000000", base.plusDays(3)),
                tx(5, "50000000", base.plusDays(4))
        ));
        txs.add(tx(99, "6000000", base.plusDays(10)));

        var result = rule.check(dossierWithTransactions(txs));

        assertThat(result).isPresent();
        assertThat(result.get().evidence()).containsEntry("historical_median", new BigDecimal("1000000"));
    }

    // --- helpers ---

    private static TxView tx(long id, String amount, OffsetDateTime timestamp) {
        return new TxView(id, 100L, 200L, TxView.DIRECTION_OUT, new BigDecimal(amount), "UZS",
                Transaction.TYPE_TRANSFER, timestamp);
    }

    private static DossierDto dossierWithTransactions(List<TxView> transactions) {
        return new DossierDto(
                1L, "Тест Клиентов", "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L),
                transactions,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
