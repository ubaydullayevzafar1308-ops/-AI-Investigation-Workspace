package uz.caseintel.api;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;
import uz.caseintel.repository.CaseRepository;
import uz.caseintel.repository.RuleHitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/dashboard/metrics — кейсов всего/за сегодня, средний Risk
 * Score, распределение срабатываний по правилам, оценка экономии времени.
 * См. ARCHITECTURE.md §14, §15 (экран Dashboard).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /**
     * Условная оценка экономии времени на один кейс за счёт платформы —
     * ориентир для демо-дашборда, не измеренное значение (см. питч:
     * "от десятков минут до нескольких часов на сложный кейс вручную").
     */
    private static final double HOURS_SAVED_PER_CASE = 3.5;

    private final CaseRepository caseRepository;
    private final RuleHitRepository ruleHitRepository;

    public DashboardController(CaseRepository caseRepository, RuleHitRepository ruleHitRepository) {
        this.caseRepository = caseRepository;
        this.ruleHitRepository = ruleHitRepository;
    }

    public record DashboardMetrics(
            long totalCases,
            long casesToday,
            Double avgRiskScore,
            Map<String, Long> ruleDistribution,
            double estimatedHoursSaved
    ) {}

    @GetMapping("/metrics")
    public DashboardMetrics metrics() {
        long total = caseRepository.count();
        long today = caseRepository.countByCreatedAtGreaterThanEqual(
                OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS));
        Double avgRiskScore = caseRepository.averageRiskScore();
        Map<String, Long> ruleDistribution = ruleHitRepository.countByRuleCode().stream()
                .collect(Collectors.toMap(
                        RuleHitRepository.RuleCountProjection::getRuleCode,
                        RuleHitRepository.RuleCountProjection::getHitCount));

        return new DashboardMetrics(total, today, avgRiskScore, ruleDistribution, total * HOURS_SAVED_PER_CASE);
    }
}
