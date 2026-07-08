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

class R02RapidMovementRuleTest {

    private final R02RapidMovementRule rule = new R02RapidMovementRule();

    @Test
    void triggers_whenSimilarAmountLeavesShortlyAfterArriving() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1L, "50000000", base),
                txOut(2L, "49000000", base.plusHours(2))
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R02");
        assertThat(result.get().evidence()).containsEntry("incoming_transaction_id", 1L);
        assertThat(result.get().evidence()).containsEntry("outgoing_transaction_id", 2L);
    }

    @Test
    void doesNotTrigger_whenGapExceedsMaxWindow() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1L, "50000000", base),
                txOut(2L, "49000000", base.plusHours(8))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenOutgoingAmountTooDifferent() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1L, "50000000", base),
                txOut(2L, "20000000", base.plusHours(2))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenOutgoingHappensBeforeIncoming() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txOut(2L, "49000000", base),
                txIn(1L, "50000000", base.plusHours(2))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_onNormalActivityOnly() {
        var base = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1L, "1000000", base),
                txIn(2L, "2000000", base.plusDays(1)),
                txOut(3L, "500000", base.plusDays(2))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    // --- helpers ---

    private static TxView txIn(long id, String amount, OffsetDateTime timestamp) {
        return new TxView(id, 100L, 200L, TxView.DIRECTION_IN, new BigDecimal(amount), "UZS",
                Transaction.TYPE_TRANSFER, timestamp);
    }

    private static TxView txOut(long id, String amount, OffsetDateTime timestamp) {
        return new TxView(id, 200L, 300L, TxView.DIRECTION_OUT, new BigDecimal(amount), "UZS",
                Transaction.TYPE_TRANSFER, timestamp);
    }

    private static DossierDto dossierWithTransactions(List<TxView> transactions) {
        return new DossierDto(
                1L, "Тест Клиентов", "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(200L),
                transactions,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
