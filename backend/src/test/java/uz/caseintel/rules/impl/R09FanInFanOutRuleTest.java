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

class R09FanInFanOutRuleTest {

    private final R09FanInFanOutRule rule = new R09FanInFanOutRule();

    @Test
    void triggers_whenManySmallInboundsFollowedByOneMatchingOutbound() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1, "10000000", base),
                txIn(2, "10000000", base.plusHours(2)),
                txIn(3, "10000000", base.plusHours(4)),
                txIn(4, "10000000", base.plusHours(6)),
                txOut(5, "40000000", base.plusHours(8))
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R09");
        assertThat(result.get().evidence()).containsEntry("incoming_count", 4);
    }

    @Test
    void doesNotTrigger_whenFewerThanMinFanInCount() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1, "10000000", base),
                txIn(2, "10000000", base.plusHours(2)),
                txOut(3, "20000000", base.plusHours(4))
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenOutgoingAmountDoesNotMatchSum() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1, "10000000", base),
                txIn(2, "10000000", base.plusHours(2)),
                txIn(3, "10000000", base.plusHours(4)),
                txIn(4, "10000000", base.plusHours(6)),
                txOut(5, "5000000", base.plusHours(8)) // сильно меньше суммы входящих
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenInboundsAreOutsideWindow() {
        var base = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                txIn(1, "10000000", base),
                txIn(2, "10000000", base.plusDays(5)),
                txIn(3, "10000000", base.plusDays(10)),
                txIn(4, "10000000", base.plusDays(15)),
                txOut(5, "40000000", base.plusDays(20))
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
