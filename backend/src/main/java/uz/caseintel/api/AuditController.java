package uz.caseintel.api;

import java.util.List;
import uz.caseintel.entity.AuditLog;
import uz.caseintel.repository.AuditLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/cases/{id}/audit — полная история расследования, включая
 * полный prompt+response каждого вызова LLM. См. ARCHITECTURE.md §14, §12.
 */
@RestController
@RequestMapping("/api/cases")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/{id}/audit")
    public List<AuditLog> getAudit(@PathVariable Long id) {
        return auditLogRepository.findByCaseEntityIdOrderByCreatedAtAsc(id);
    }
}
