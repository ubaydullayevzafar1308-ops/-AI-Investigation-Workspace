package uz.caseintel.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import uz.caseintel.evidence.Evidence;
import uz.caseintel.evidence.EvidenceBundle;
import org.junit.jupiter.api.Test;

class RiskEngineServiceTest {

    private final RiskEngineService service = new RiskEngineService();

    @Test
    void sumsRuleHitWeights_ignoringZeroWeightInformationalItems() {
        var bundle = new EvidenceBundle(1L, List.of(
                ruleHit("R01", 20),
                ruleHit("R03", 25),
                informational(Evidence.TYPE_RELATION, "Related company: OOO Barakat")
        ), 45);

        var result = service.score(bundle);

        assertThat(result.score()).isEqualTo(45);
        assertThat(result.level()).isEqualTo(RiskResult.LEVEL_MEDIUM);
    }

    @Test
    void appliesRepeatOffenderBonus_whenPreviousAlertPresent() {
        var bundle = new EvidenceBundle(1L, List.of(
                ruleHit("R01", 20),
                new Evidence(Evidence.TYPE_PREVIOUS_ALERT, "Previous alert: #482 (escalated)", 0, Map.of())
        ), 20);

        var result = service.score(bundle);

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.level()).isEqualTo(RiskResult.LEVEL_MEDIUM);
    }

    @Test
    void doesNotApplyBonus_whenNoPreviousAlert() {
        var bundle = new EvidenceBundle(1L, List.of(ruleHit("R01", 20)), 20);

        var result = service.score(bundle);

        assertThat(result.score()).isEqualTo(20);
        assertThat(result.level()).isEqualTo(RiskResult.LEVEL_LOW);
    }

    @Test
    void capsScoreAt100_evenWithManyHighWeightRules() {
        var bundle = new EvidenceBundle(1L, List.of(
                ruleHit("R03", 25),
                ruleHit("R10", 25),
                ruleHit("R07", 20),
                ruleHit("R01", 20),
                ruleHit("R09", 15)
        ), 105);

        var result = service.score(bundle);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.level()).isEqualTo(RiskResult.LEVEL_HIGH);
    }

    @Test
    void levelBoundaries_areInclusiveAtThresholds() {
        assertThat(service.score(bundleWithScore(59)).level()).isEqualTo(RiskResult.LEVEL_MEDIUM);
        assertThat(service.score(bundleWithScore(60)).level()).isEqualTo(RiskResult.LEVEL_HIGH);
        assertThat(service.score(bundleWithScore(29)).level()).isEqualTo(RiskResult.LEVEL_LOW);
        assertThat(service.score(bundleWithScore(30)).level()).isEqualTo(RiskResult.LEVEL_MEDIUM);
    }

    @Test
    void emptyBundle_scoresZeroAndLow() {
        var bundle = new EvidenceBundle(1L, List.of(), 0);

        var result = service.score(bundle);

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo(RiskResult.LEVEL_LOW);
    }

    private static Evidence ruleHit(String code, int weight) {
        return new Evidence(Evidence.TYPE_RULE_HIT, "Rule " + code, weight, Map.of());
    }

    private static Evidence informational(String type, String title) {
        return new Evidence(type, title, 0, Map.of());
    }

    private static EvidenceBundle bundleWithScore(int weight) {
        return new EvidenceBundle(1L, List.of(ruleHit("R01", weight)), weight);
    }
}
