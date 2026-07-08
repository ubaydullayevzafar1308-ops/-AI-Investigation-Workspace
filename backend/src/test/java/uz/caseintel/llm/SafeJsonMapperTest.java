package uz.caseintel.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CompanyView;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;
import uz.caseintel.casebuilder.dto.DossierDto.PastAlertView;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.evidence.Evidence;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.risk.RiskResult;
import org.junit.jupiter.api.Test;

class SafeJsonMapperTest {

    private final SafeJsonMapper mapper = new SafeJsonMapper();

    /**
     * ГЛАВНЫЙ ТЕСТ-ИНВАРИАНТ БЕЗОПАСНОСТИ (см. AI_LAYER_ARCHITECTURE.md §3,
     * §11 чеклист): сериализованный SafeCaseJson для golden case не
     * должен содержать НИ ОДНОГО реального ФИО/ИНН/названия компании.
     */
    @Test
    void goldenCase_containsNoRealNamesInSerializedOutput() {
        String realClientName = "Каримов Алишер Ботирович";
        String realCompany1 = "OOO Barakat Trade";
        String realCompany2 = "OOO Vega Import";

        var dossier = new DossierDto(
                1L, realClientName, "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L, 101L),
                List.of(),
                List.of(
                        new RelationView("company", 10L, realCompany1, "director", false),
                        new RelationView("company", 11L, realCompany2, "director", false)
                ),
                List.of(
                        new CompanyView(10L, realCompany1, LocalDate.of(2026, 1, 1), false, "director",
                                new BigDecimal("850000000")),
                        new CompanyView(11L, realCompany2, LocalDate.of(2026, 2, 1), false, "director",
                                new BigDecimal("400000000"))
                ),
                List.of(new PastAlertView(482L, 55L, "escalated", java.time.OffsetDateTime.now().minusMonths(3))),
                List.of(new CycleView(
                        List.of(realClientName, realCompany1, realCompany2, realClientName),
                        new BigDecimal("850000000"),
                        9
                ))
        );

        var explanation = new ExplanationDto(91, RiskResult.LEVEL_HIGH, List.of(
                new ExplanationDto.Reason(
                        "Круговая схема переводов", 25,
                        "%s → %s → %s → %s, оборот 850 млн UZS".formatted(
                                realClientName, realCompany1, realCompany2, realClientName)
                ),
                new ExplanationDto.Reason("Дробление сумм", 20, "5 операций по 95-99 млн за 2 дня")
        ));

        var evidenceBundle = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R03: Circular flow", 25,
                        Map.of("path", List.of(realClientName, realCompany1, realCompany2, realClientName))),
                new Evidence(Evidence.TYPE_RELATION,
                        "Related company: %s (blacklisted, director)".formatted(realCompany1), 0,
                        Map.of("company_name", realCompany1))
        ), 45);

        var risk = new RiskResult(91, RiskResult.LEVEL_HIGH);

        SafeCaseJson result = mapper.toSafeJson(risk, explanation, evidenceBundle, dossier);

        String serialized = result.toString();

        assertThat(serialized).doesNotContain(realClientName);
        assertThat(serialized).doesNotContain(realCompany1);
        assertThat(serialized).doesNotContain(realCompany2);
        assertThat(serialized).doesNotContain("12345678901234");
        assertThat(serialized).doesNotContain("+998901234567");
    }

    @Test
    void originalClientIsAlwaysK1() {
        var dossier = minimalDossier("Иванов Иван Иванович");
        var result = mapper.toSafeJson(
                new RiskResult(10, RiskResult.LEVEL_LOW),
                new ExplanationDto(10, RiskResult.LEVEL_LOW, List.of()),
                new EvidenceBundle(1L, List.of(), 0),
                dossier
        );

        assertThat(result.client().pseudonym()).isEqualTo("Клиент К-1");
    }

    @Test
    void longerNameReplacedBeforeShorterSubstringName() {
        String longName = "Иванов Иван Иванович";
        var dossier = new DossierDto(
                1L, longName, "12345678901234", "+998901234567", "device-1",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L), List.of(),
                List.of(new RelationView("client", 2L, "Иван", "family", false)),
                List.of(), List.of(), List.of()
        );

        var explanation = new ExplanationDto(10, RiskResult.LEVEL_LOW, List.of(
                new ExplanationDto.Reason("Родственная связь", 5, "Связь с " + longName)
        ));

        var result = mapper.toSafeJson(
                new RiskResult(10, RiskResult.LEVEL_LOW),
                explanation,
                new EvidenceBundle(1L, List.of(), 0),
                dossier
        );

        String detail = result.reasons().get(0).detail();
        assertThat(detail).doesNotContain(longName);
        assertThat(detail).doesNotContain("Иванов");
    }

    @Test
    void numericAggregatesAreNotTouched() {
        var dossier = minimalDossier("Тестов Тест Тестович");
        var evidenceBundle = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring", 20,
                        Map.of("total_amount", new BigDecimal("95000000"), "count", 5))
        ), 20);

        var result = mapper.toSafeJson(
                new RiskResult(20, RiskResult.LEVEL_LOW),
                new ExplanationDto(20, RiskResult.LEVEL_LOW, List.of()),
                evidenceBundle,
                dossier
        );

        assertThat(result.evidence().get(0).aggregates())
                .containsEntry("total_amount", new BigDecimal("95000000"))
                .containsEntry("count", 5);
    }

    private static DossierDto minimalDossier(String fullName) {
        return new DossierDto(
                1L, fullName, "00000000000000", "+998900000000", "device-x",
                "Ташкент", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(100L), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
