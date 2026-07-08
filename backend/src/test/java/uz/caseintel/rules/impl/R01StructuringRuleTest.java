package uz.caseintel.rules.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.entity.Transaction;
import org.junit.jupiter.api.Test;

class R01StructuringRuleTest {

    private final R01StructuringRule rule = new R01StructuringRule();

    @Test
    void triggers_whenThreeNearThresholdTransfersWithinThreeDays() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "95000000", base),
                tx(2L, "97000000", base.plusDays(1)),
                tx(3L, "96000000", base.plusDays(2))
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R01");
        assertThat(result.get().weight()).isEqualTo(20);
        assertThat(result.get().evidence()).containsEntry("count", 3);
        @SuppressWarnings("unchecked")
        var txIds = (List<Long>) result.get().evidence().get("transaction_ids");
        assertThat(txIds).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void doesNotTrigger_whenOnlyTwoNearThresholdTransfers() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "95000000", base),
                tx(2L, "97000000", base.plusDays(1))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenTransactionsSpreadBeyondWindow() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "95000000", base),
                tx(2L, "97000000", base.plusDays(5)),
                tx(3L, "96000000", base.plusDays(10))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenAmountsAreFarBelowThreshold() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "10000000", base),
                tx(2L, "12000000", base.plusDays(1)),
                tx(3L, "9000000", base.plusDays(2))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenAmountsAreAtOrAboveThreshold() {
        // Ровно на пороге или выше — это уже не "уход от порога", а сама операция
        // обязательного контроля; правило намеренно ловит только диапазон [0.9*T, T).
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "100000000", base),
                tx(2L, "110000000", base.plusDays(1)),
                tx(3L, "120000000", base.plusDays(2))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void ignoresUnrelatedTransactions_mixedInWithStructuringGroup() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(1L, "95000000", base),
                tx(99L, "500000", base.plusHours(2)),   // мелкая, не в диапазоне — игнорируется
                tx(2L, "97000000", base.plusDays(1)),
                tx(3L, "96000000", base.plusDays(2))
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        @SuppressWarnings("unchecked")
        var txIds = (List<Long>) result.get().evidence().get("transaction_ids");
        assertThat(txIds).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // --- helpers ---

    private static TxView tx(long id, String amount, OffsetDateTime timestamp) {
        return new TxView(
                id,
                100L, 200L,
                TxView.DIRECTION_OUT,
                new BigDecimal(amount),
                "UZS",
                Transaction.TYPE_TRANSFER,
                timestamp
        );
    }

    private static DossierDto dossierWithTransactions(List<TxView> transactions) {
        return new DossierDto(
                1L, "Тест Клиентов", "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L),
                transactions,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
