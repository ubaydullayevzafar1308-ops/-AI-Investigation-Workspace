package uz.caseintel.api;

import uz.caseintel.casebuilder.CaseBuilderService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.repository.AlertRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
     * Список алертов: опциональные фильтры status/severity (комбинируются
     * через AND — см. AlertRepository.search), сортировка created_at desc,
     * пагинация page/size. Возвращает AlertSummaryDto, а не саму Alert
     * entity — см. javadoc AlertSummaryDto. @Transactional нужен, чтобы
     * getTransaction()/getClient() внутри from() работали (open-in-view
     * выключен).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public Page<AlertSummaryDto> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return alertRepository.search(status, severity, pageable).map(AlertSummaryDto::from);
    }

    /**
     * Запускает Case Builder для алерта -> возвращает готовый кейс.
     * Перевод алерта в investigating происходит внутри buildCase, в одной
     * транзакции с созданием кейса.
     */
    @PostMapping("/{id}/investigate")
    public ReadyCaseDto investigate(@PathVariable Long id) {
        return caseBuilderService.buildCase(id);
    }
}
