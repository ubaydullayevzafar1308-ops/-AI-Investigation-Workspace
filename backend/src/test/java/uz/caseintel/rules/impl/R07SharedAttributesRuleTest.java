package uz.caseintel.rules.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.entity.Relationship;
import org.junit.jupiter.api.Test;

class R07SharedAttributesRuleTest {

    private final R07SharedAttributesRule rule = new R07SharedAttributesRule();

    @Test
    void triggers_whenSharedDeviceWithBlacklistedClient() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("client", 55L, "Клиент #55", Relationship.TYPE_SAME_DEVICE, true)
        ));

        var result = rule.check(dossier);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo("R07");
        assertThat(result.get().evidence()).containsEntry("counterpart_id", 55L);
    }

    @Test
    void doesNotTrigger_whenCounterpartNotBlacklisted() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("client", 55L, "Клиент #55", Relationship.TYPE_SAME_DEVICE, false)
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenRelationTypeIrrelevant() {
        var dossier = dossierWithRelations(List.of(
                new RelationView("client", 55L, "Клиент #55", Relationship.TYPE_FREQUENT_COUNTERPARTY, true)
        ));

        assertThat(rule.check(dossier)).isEmpty();
    }

    @Test
    void doesNotTrigger_whenNoRelations() {
        assertThat(rule.check(dossierWithRelations(List.of()))).isEmpty();
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
