package uz.caseintel.api;

import java.util.List;
import uz.caseintel.casebuilder.CaseBuilderService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.entity.Alert;
import uz.caseintel.repository.AlertRepository;
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

    /** Список алертов, опциональные фильтры status/severity. */
    @GetMapping
    public List<Alert> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        if (status != null) {
            return alertRepository.findByStatus(status);
        }
        if (severity != null) {
            return alertRepository.findBySeverity(severity);
        }
        return alertRepository.findAll();
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
