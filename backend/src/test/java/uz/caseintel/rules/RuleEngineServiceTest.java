package uz.caseintel.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.entity.Transaction;
import uz.caseintel.rules.impl.R01StructuringRule;
import uz.caseintel.rules.impl.R02RapidMovementRule;
import uz.caseintel.rules.impl.R03CircularFlowRule;
import uz.caseintel.rules.impl.R04NewEntitySpikeRule;
import uz.caseintel.rules.impl.R05DormantAwakeningRule;
import uz.caseintel.rules.impl.R06CashIntensiveRule;
import uz.caseintel.rules.impl.R07SharedAttributesRule;
import uz.caseintel.rules.impl.R08AmountAnomalyRule;
import uz.caseintel.rules.impl.R09FanInFanOutRule;
import uz.caseintel.rules.impl.R10HighRiskCounterpartyRule;
import org.junit.jupiter.api.Test;

/**
 * Тест уровня раннера: runAll на досье, повторяющем structuring-схему
 * сидера (StructuringScheme: 4 перевода 95.2–98.8 млн с шагом 14 часов),
 * должен вернуть ровно один хит — R01 — с transaction_ids всех четырёх
 * операций. Полный состав правил (R01..R10) собран вручную так же, как
 * его инжектит Spring, чтобы заодно проверить отсутствие ложных
 * срабатываний остальных правил на этом досье.
 */
class RuleEngineServiceTest {

    private final RuleEngineService engine = new RuleEngineService(List.of(
            new R01StructuringRule(),
            new R02RapidMovementRule(),
            new R03CircularFlowRule(),
            new R04NewEntitySpikeRule(),
            new R05DormantAwakeningRule(),
            new R06CashIntensiveRule(),
            new R07SharedAttributesRule(),
            new R08AmountAnomalyRule(),
            new R09FanInFanOutRule(),
            new R10HighRiskCounterpartyRule()
    ));

    @Test
    void runAll_onStructuringSchemeDossier_returnsOnlyR01HitWithAllFourTransactionIds() {
        var base = OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        var dossier = dossierWithTransactions(List.of(
                tx(101L, "96000000", base),
                tx(102L, "97500000", base.plusHours(14)),
                tx(103L, "95200000", base.plusHours(28)),
                tx(104L, "98800000", base.plusHours(42))
        ));

        List<RuleResult> hits = engine.runAll(dossier);

        assertThat(hits).hasSize(1);
        RuleResult hit = hits.get(0);
        assertThat(hit.code()).isEqualTo("R01");
        assertThat(hit.weight()).isEqualTo(20);
        @SuppressWarnings("unchecked")
        var txIds = (List<Long>) hit.evidence().get("transaction_ids");
        assertThat(txIds).containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
    }

    @Test
    void runAll_onEmptyDossier_returnsNoHits() {
        assertThat(engine.runAll(dossierWithTransactions(List.of()))).isEmpty();
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
                List.of(),
                List.of()  // moneyCycles
        );
    }
}
