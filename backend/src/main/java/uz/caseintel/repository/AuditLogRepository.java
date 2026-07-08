package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Полная история расследования по кейсу — GET /api/cases/{id}/audit, сортировка по времени. */
    List<AuditLog> findByCaseEntityIdOrderByCreatedAtAsc(Long caseId);
}
