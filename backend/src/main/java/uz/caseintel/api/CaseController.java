package uz.caseintel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import uz.caseintel.audit.AuditService;
import uz.caseintel.entity.Case;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.repository.CaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/cases, GET /api/cases/{id}, GET /api/cases/{id}/evidence,
 * GET /api/cases/{id}/explanation, PATCH /api/cases/{id}/decision.
 * См. ARCHITECTURE.md §14.
 *
 * Возвращает CaseSummaryDto, а не саму Case entity — см. javadoc
 * CaseSummaryDto для причин (LAZY-связи + сырые JSON-строки в колонках).
 * @Transactional нужен, чтобы getAlert()/getClient() внутри from()
 * не бросали LazyInitializationException вне сессии Hibernate.
 */
@RestController
@RequestMapping("/api/cases")
@Transactional(readOnly = true)
public class CaseController {

    private final CaseRepository caseRepository;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public CaseController(CaseRepository caseRepository, AuditService audit, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<CaseSummaryDto> list() {
        return caseRepository.findAll().stream().map(CaseSummaryDto::from).toList();
    }

    /** Сводка по кейсу; полные dossier/evidence/explanation — через отдельные эндпоинты ниже. */
    @GetMapping("/{id}")
    public CaseSummaryDto get(@PathVariable Long id) {
        return CaseSummaryDto.from(findOrThrow(id));
    }

    @GetMapping("/{id}/evidence")
    public EvidenceBundle getEvidence(@PathVariable Long id) {
        Case c = findOrThrow(id);
        return parseJson(c.getEvidenceJson(), EvidenceBundle.class);
    }

    @GetMapping("/{id}/explanation")
    public ExplanationDto getExplanation(@PathVariable Long id) {
        Case c = findOrThrow(id);
        return parseJson(c.getExplanationJson(), ExplanationDto.class);
    }

    public record DecisionRequest(String status, String comment) {}

    /**
     * status: одно из Case.STATUS_* (approved | rejected | escalated).
     * Записывает решение и пишет в Audit Log (см. AuditService.logDecision).
     */
    @PatchMapping("/{id}/decision")
    @Transactional
    public CaseSummaryDto decide(@PathVariable Long id, @RequestBody DecisionRequest request) {
        Case c = findOrThrow(id);
        c.setStatus(request.status());
        c.setAnalystDecision(request.comment());
        c.setClosedAt(OffsetDateTime.now());
        Case saved = caseRepository.save(c);

        // Имя аналитика пока не приходит через auth-контекст (аутентификации
        // ещё нет в системе) — placeholder "analyst" до появления авторизации.
        audit.logDecision(saved, "analyst");

        return CaseSummaryDto.from(saved);
    }

    private Case findOrThrow(Long id) {
        return caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + id));
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored snapshot for case", e);
        }
    }
}
