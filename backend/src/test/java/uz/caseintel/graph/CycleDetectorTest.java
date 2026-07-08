package uz.caseintel.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import uz.caseintel.graph.CycleDetector.MoneyEdge;
import org.junit.jupiter.api.Test;

class CycleDetectorTest {

    private static final Map<String, String> LABELS = Map.of(
            "client_1", "К-1",
            "company_1", "OOO Barakat",
            "company_2", "OOO Vega"
    );

    @Test
    void findsSimpleThreeNodeCycle() {
        var edges = List.of(
                new MoneyEdge("client_1", "company_1", new BigDecimal("100000000"), 3),
                new MoneyEdge("company_1", "company_2", new BigDecimal("95000000"), 2),
                new MoneyEdge("company_2", "client_1", new BigDecimal("90000000"), 4)
        );

        var detector = new CycleDetector(edges, LABELS);
        var cycles = detector.findCyclesFrom("client_1");

        assertThat(cycles).hasSize(1);
        var cycle = cycles.get(0);
        assertThat(cycle.pathLabels()).containsExactly("К-1", "OOO Barakat", "OOO Vega", "К-1");
        assertThat(cycle.totalAmount()).isEqualByComparingTo("285000000");
        assertThat(cycle.transactionCount()).isEqualTo(9);
    }

    @Test
    void doesNotReportTwoNodePingPongAsCycle() {
        var edges = List.of(
                new MoneyEdge("client_1", "company_1", new BigDecimal("50000000"), 1),
                new MoneyEdge("company_1", "client_1", new BigDecimal("48000000"), 1)
        );

        var detector = new CycleDetector(edges, LABELS);
        var cycles = detector.findCyclesFrom("client_1");

        assertThat(cycles).isEmpty();
    }

    @Test
    void returnsEmpty_whenNoPathLeadsBackToStart() {
        var edges = List.of(
                new MoneyEdge("client_1", "company_1", new BigDecimal("50000000"), 1),
                new MoneyEdge("company_1", "company_2", new BigDecimal("48000000"), 1)
        );

        var detector = new CycleDetector(edges, LABELS);
        assertThat(detector.findCyclesFrom("client_1")).isEmpty();
    }

    @Test
    void doesNotReportSameCycleTwice() {
        var edges = List.of(
                new MoneyEdge("client_1", "company_1", new BigDecimal("100000000"), 1),
                new MoneyEdge("company_1", "company_2", new BigDecimal("95000000"), 1),
                new MoneyEdge("company_2", "client_1", new BigDecimal("90000000"), 1)
        );

        var detector = new CycleDetector(edges, LABELS);
        var cycles = detector.findCyclesFrom("client_1");

        assertThat(cycles).hasSize(1);
    }

    @Test
    void ignoresIsolatedNode() {
        var detector = new CycleDetector(List.of(), LABELS);
        assertThat(detector.findCyclesFrom("client_1")).isEmpty();
    }
}
