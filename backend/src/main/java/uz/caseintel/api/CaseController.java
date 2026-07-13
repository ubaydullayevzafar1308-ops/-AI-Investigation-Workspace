package uz.caseintel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import uz.caseintel.audit.AuditService;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.entity.Case;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.repository.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    private final CaseRepository caseRepository;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public CaseController(CaseRepository caseRepository, AuditService audit, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * Без totalElements/totalPages — сейчас у списка кейсов нет фронтенд-
     * потребителя, которому они нужны (в отличие от /api/alerts), поэтому
     * Slice вместо Page — без лишнего COUNT(*) на каждый запрос.
     */
    @GetMapping
    public Slice<CaseSummaryDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return caseRepository.findAllBy(pageable).map(CaseSummaryDto::from);
    }

    /** Полное досье: summary + разобранные dossier/evidence/explanation из JSONB. */
    @GetMapping("/{id}")
    public CaseDetailDto get(@PathVariable Long id) {
        Case c = findOrThrow(id);
        List<String> warnings = new ArrayList<>();
        DossierDto dossier = parseJson(c.getDossierJson(), DossierDto.class, warnings);
        EvidenceBundle evidence = parseJson(c.getEvidenceJson(), EvidenceBundle.class, warnings);
        ExplanationDto explanation = parseJson(c.getExplanationJson(), ExplanationDto.class, warnings);
        return new CaseDetailDto(
                c.getId(),
                c.getAlert() != null ? c.getAlert().getId() : null,
                c.getClient() != null ? c.getClient().getId() : null,
                c.getRiskScore(),
                c.getRiskLevel(),
                c.getStatus(),
                c.getAnalystDecision(),
                c.getCreatedAt(),
                c.getClosedAt(),
                dossier,
                evidence,
                explanation,
                warnings.isEmpty() ? null : String.join("; ", warnings)
        );
    }

    @GetMapping("/{id}/evidence")
    public EvidenceBundle getEvidence(@PathVariable Long id) {
        Case c = findOrThrow(id);
        return parseJson(c.getEvidenceJson(), EvidenceBundle.class, null);
    }

    @GetMapping("/{id}/explanation")
    public ExplanationDto getExplanation(@PathVariable Long id) {
        Case c = findOrThrow(id);
        return parseJson(c.getExplanationJson(), ExplanationDto.class, null);
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

    /**
     * Безопасный парсинг JSONB-снапшота: битый/несовместимый JSON больше
     * не роняет запрос 500-кой — логируем полный stack trace (реальная
     * порча данных должна быть видна в логах) и возвращаем null для этого
     * поля, добавляя человекочитаемое сообщение в warnings (если вызывающий
     * код его передал — GET /{id}/evidence и /{id}/explanation отдают
     * "голый" тип без обёртки для warning, поэтому там warnings == null).
     */
    private <T> T parseJson(String json, Class<T> type, List<String> warnings) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("Failed to parse stored {} snapshot", type.getSimpleName(), e);
            if (warnings != null) {
                warnings.add("Failed to parse " + type.getSimpleName() + " snapshot");
            }
            return null;
        }
    }
}
