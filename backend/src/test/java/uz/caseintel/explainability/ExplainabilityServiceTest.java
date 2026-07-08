package uz.caseintel.explainability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import uz.caseintel.evidence.Evidence;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.risk.RiskResult;
import org.junit.jupiter.api.Test;

class ExplainabilityServiceTest {

    private final ExplainabilityService service = new ExplainabilityService();

    @Test
    void buildsReasonsFromRuleHits_sortedByContributionDescending() {
        var evidence = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring (дробление сумм)", 20,
                        Map.of("count", 5)),
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R03: Circular flow (круговая схема переводов)", 25,
                        Map.of("total_amount", "850000000"))
        ), 45);
        var risk = new RiskResult(45, RiskResult.LEVEL_MEDIUM);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.riskScore()).isEqualTo(45);
        assertThat(explanation.riskLevel()).isEqualTo(RiskResult.LEVEL_MEDIUM);
        assertThat(explanation.reasons()).hasSize(2);
        assertThat(explanation.reasons().get(0).factor()).isEqualTo("Circular flow (круговая схема переводов)");
        assertThat(explanation.reasons().get(0).contribution()).isEqualTo(25);
        assertThat(explanation.reasons().get(1).factor()).isEqualTo("Structuring (дробление сумм)");
    }

    @Test
    void ignoresZeroWeightInformationalItems() {
        var evidence = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring", 20, Map.of()),
                new Evidence(Evidence.TYPE_ANOMALY, "Night transactions: 6 операций", 0, Map.of())
        ), 20);
        var risk = new RiskResult(20, RiskResult.LEVEL_LOW);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.reasons()).hasSize(1);
        assertThat(explanation.reasons().get(0).factor()).isEqualTo("Structuring");
    }

    @Test
    void addsRepeatOffenderReason_whenPreviousAlertPresent() {
        var evidence = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring", 20, Map.of()),
                new Evidence(Evidence.TYPE_PREVIOUS_ALERT, "Previous alert: #482 (escalated)", 0, Map.of())
        ), 20);
        var risk = new RiskResult(30, RiskResult.LEVEL_MEDIUM);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.reasons()).hasSize(2);
        assertThat(explanation.reasons()).anySatisfy(r -> {
            assertThat(r.factor()).isEqualTo("Повторный фигурант");
            assertThat(r.contribution()).isEqualTo(10);
        });
    }

    @Test
    void annotatesCap_whenRawSumExceedsCappedScore() {
        var evidence = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R03: Circular flow", 25, Map.of()),
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R10: High-risk counterparty", 25, Map.of()),
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R07: Shared attributes", 20, Map.of()),
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring", 20, Map.of()),
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R09: Fan-in/Fan-out", 15, Map.of())
        ), 105);
        var risk = new RiskResult(100, RiskResult.LEVEL_HIGH);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.reasons()).hasSize(5);
        var lastReason = explanation.reasons().get(explanation.reasons().size() - 1);
        assertThat(lastReason.factor()).isEqualTo("Fan-in/Fan-out");
        assertThat(lastReason.detail()).contains("капнуто до 100");
    }

    @Test
    void doesNotAnnotateCap_whenRawSumEqualsScore() {
        var evidence = new EvidenceBundle(1L, List.of(
                new Evidence(Evidence.TYPE_RULE_HIT, "Rule R01: Structuring", 20, Map.of())
        ), 20);
        var risk = new RiskResult(20, RiskResult.LEVEL_LOW);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.reasons().get(0).detail()).doesNotContain("капнуто");
    }

    @Test
    void emptyEvidence_producesEmptyReasons() {
        var evidence = new EvidenceBundle(1L, List.of(), 0);
        var risk = new RiskResult(0, RiskResult.LEVEL_LOW);

        var explanation = service.explain(risk, evidence);

        assertThat(explanation.reasons()).isEmpty();
    }
}
