package uz.caseintel.api;

import java.util.List;
import uz.caseintel.casebuilder.CaseBuilderService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.entity.Alert;
import uz.caseintel.repository.AlertRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/alerts, POST /api/alerts/{id}/investigate — см. ARCHITECTURE.md §14.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository alertRepository;
    private final CaseBuilderService caseBuilderService;

    public AlertController(AlertRepository alertRepository, CaseBuilderService caseBuilderService) {
        this.alertRepository = alertRepository;
        this.caseBuilderService = caseBuilderService;
    }

    /**
     * Список алертов, опциональные фильтры status/severity.
     * Возвращает AlertSummaryDto, а не саму Alert entity — см. javadoc
     * AlertSummaryDto. @Transactional нужен, чтобы getTransaction()/getClient()
     * внутри from() работали (open-in-view выключен).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<AlertSummaryDto> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        List<Alert> alerts;
        if (status != null) {
            alerts = alertRepository.findByStatus(status);
        } else if (severity != null) {
            alerts = alertRepository.findBySeverity(severity);
        } else {
            alerts = alertRepository.findAll();
        }
        return alerts.stream().map(AlertSummaryDto::from).toList();
    }

    /** Запускает Case Builder для алерта -> возвращает готовый кейс. */
    @PostMapping("/{id}/investigate")
    public ReadyCaseDto investigate(@PathVariable Long id) {
        ReadyCaseDto readyCase = caseBuilderService.buildCase(id);

        alertRepository.findById(id).ifPresent(alert -> {
            alert.setStatus(Alert.STATUS_INVESTIGATING);
            alertRepository.save(alert);
        });

        return readyCase;
    }
}
