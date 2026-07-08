package uz.caseintel.rules.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.entity.Relationship;
import org.junit.jupiter.api.Test;

class R10HighRiskCounterpartyRuleTest {

    private final R10HighRiskCounterpartyRule rule = new R10HighRiskCounterpartyRule();

    @Test
    void triggers_whenFrequentCounterpartyIsBlacklisted() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("company", 77L, "OOO Vega", Relationship.TYPE_FREQUENT_COUNTERPARTY, true)
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R10");
        assertThat(result.get().weight()).isEqualTo(25);
    }

    @Test
    void doesNotTrigger_whenSameDeviceInsteadOfFrequentCounterparty() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("client", 77L, "Клиент #77", Relationship.TYPE_SAME_DEVICE, true)
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenCounterpartNotBlacklisted() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("company", 77L, "OOO Vega", Relationship.TYPE_FREQUENT_COUNTERPARTY, false)
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    private static DossierDto dossierWithRelations(List<RelationView> relations) {
        return new DossierDto(
                1L, "Тест Клиентов", "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L),
                List.of(),
                relations,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
