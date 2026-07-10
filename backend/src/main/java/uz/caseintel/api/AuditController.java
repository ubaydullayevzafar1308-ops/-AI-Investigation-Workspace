package uz.caseintel.api;

import java.util.List;
import uz.caseintel.repository.AuditLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/cases/{id}/audit — история расследования (prompt/response
 * обрезаны до 200 символов); GET /api/cases/{id}/audit/{eventId} — одна
 * полная запись без обрезки. См. ARCHITECTURE.md §14, §12.
 */
@RestController
@RequestMapping("/api/cases")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/{id}/audit")
    public List<AuditLogSummaryDto> getAudit(@PathVariable Long id) {
        return auditLogRepository.findByCaseEntityIdOrderByCreatedAtAsc(id).stream()
                .map(AuditLogSummaryDto::from)
                .toList();
    }

    @GetMapping("/{id}/audit/{eventId}")
    public AuditLogDetailDto getAuditEvent(@PathVariable Long id, @PathVariable Long eventId) {
        return auditLogRepository.findByIdAndCaseEntityId(eventId, id)
                .map(AuditLogDetailDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Audit event " + eventId + " not found for case " + id));
    }
}
