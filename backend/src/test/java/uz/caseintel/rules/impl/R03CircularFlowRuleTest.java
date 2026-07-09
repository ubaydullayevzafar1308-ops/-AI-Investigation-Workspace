package uz.caseintel.rules.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;
import org.junit.jupiter.api.Test;

class R03CircularFlowRuleTest {

    private final R03CircularFlowRule rule = new R03CircularFlowRule();

    @Test
    void triggers_onCycleAboveTurnoverThreshold() {
        // Повторяет circular-схему сидера: client → A → B → client, 875 млн.
        var dossier = dossierWithCycles(List.of(new CycleView(
                List.of("Клиент К", "OOO Zarafshon Capital", "OOO Registon Holding", "Клиент К"),
                new BigDecimal("875000000"), 3
        )));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R03");
        assertThat(result.get().weight()).isEqualTo(25);
        assertThat(result.get().evidence()).containsEntry("total_amount", new BigDecimal("875000000"));
        assertThat(result.get().explanation())
                .contains("Клиент К → OOO Zarafshon Capital → OOO Registon Holding → Клиент К")
                .contains("общий оборот");
    }

    @Test
    void picksBiggestCycle_whenSeveralFound() {
        var dossier = dossierWithCycles(List.of(
                new CycleView(List.of("К", "A", "B", "К"), new BigDecimal("150000000"), 3),
                new CycleView(List.of("К", "C", "D", "К"), new BigDecimal("900000000"), 4)
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().evidence()).containsEntry("total_amount", new BigDecimal("900000000"));
        assertThat(result.get().evidence()).containsEntry("cycles_found", 2);
    }

    @Test
    void doesNotTrigger_onOrdinaryClientWithoutCycles() {
        assertThat(rule.check(dossierWithCycles(List.of()))).isEmpty();
    }

    @Test
    void doesNotTrigger_whenCycleTurnoverBelowThreshold() {
        // Мелкий круговой перевод (50 млн < MIN_CYCLE_TURNOVER=100 млн) — не схема.
        var dossier = dossierWithCycles(List.of(new CycleView(
                List.of("К", "A", "B", "К"), new BigDecimal("50000000"), 3
        )));

        assertThat(rule.check(dossier)).isEmpty();
    }

    // --- helpers ---

    private static DossierDto dossierWithCycles(List<CycleView> cycles) {
        return new DossierDto(
                1L, "Тест Клиентов", "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                cycles
        );
    }
}
