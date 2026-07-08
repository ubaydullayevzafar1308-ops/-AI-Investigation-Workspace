package uz.caseintel.api;

import java.time.Duration;
import java.util.List;
import uz.caseintel.entity.Case;
import uz.caseintel.repository.CaseRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/dashboard/metrics — время на кейс, обработано, экономия часов.
 * См. ARCHITECTURE.md §14, §15 (экран Dashboard).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /**
     * Условная оценка среднего времени РУЧНОГО расследования одного кейса
     * без платформы — используется только для метрики "экономия часов"
     * на демо-дашборде. Это не измеренное значение, а ориентир из
     * питча ("от десятков минут до нескольких часов на сложный кейс") —
     * стоит заменить реальной цифрой из интервью с банком, если она
     * появится, вместо константы для хакатон-демо.
     */
    private static final double ESTIMATED_MANUAL_MINUTES_PER_CASE = 45.0;

    private final CaseRepository caseRepository;

    public DashboardController(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    public record DashboardMetrics(
            long totalCasesProcessed,
            long casesOpen,
            long casesApproved,
            long casesRejected,
            long casesEscalated,
            Double avgMinutesPerCase,
            double estimatedHoursSaved
    ) {}

    @GetMapping("/metrics")
    public DashboardMetrics metrics() {
        List<Case> allCases = caseRepository.findAll();

        long total = allCases.size();
        long open = countByStatus(allCases, Case.STATUS_OPEN);
        long approved = countByStatus(allCases, Case.STATUS_APPROVED);
        long rejected = countByStatus(allCases, Case.STATUS_REJECTED);
        long escalated = countByStatus(allCases, Case.STATUS_ESCALATED);

        List<Case> closedWithDuration = allCases.stream()
                .filter(c -> c.getClosedAt() != null && c.getCreatedAt() != null)
                .toList();

        Double avgMinutes = closedWithDuration.isEmpty() ? null : closedWithDuration.stream()
                .mapToLong(c -> Duration.between(c.getCreatedAt(), c.getClosedAt()).toMinutes())
                .average()
                .orElse(0.0);

        double estimatedHoursSaved = avgMinutes == null
                ? 0.0
                : closedWithDuration.size() * (ESTIMATED_MANUAL_MINUTES_PER_CASE - avgMinutes) / 60.0;

        return new DashboardMetrics(total, open, approved, rejected, escalated, avgMinutes, estimatedHoursSaved);
    }

    private long countByStatus(List<Case> cases, String status) {
        return cases.stream().filter(c -> status.equals(c.getStatus())).count();
    }
}
