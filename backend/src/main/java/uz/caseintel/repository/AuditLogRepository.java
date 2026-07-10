package uz.caseintel.repository;

import java.util.List;
import java.util.Optional;
import uz.caseintel.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Полная история расследования по кейсу — GET /api/cases/{id}/audit, сортировка по времени. */
    List<AuditLog> findByCaseEntityIdOrderByCreatedAtAsc(Long caseId);

    /**
     * Первая запись llm_called кейса. Пайплайн вызывает LLM в строгом
     * порядке (сначала explainRisk, потом generateReport — см.
     * CaseBuilderService шаг ⑧), поэтому первый llm_called по id — это
     * всегда ответ explainRisk. Используется идемпотентным повтором
     * buildCase, чтобы вернуть тот же humanExplanation без нового вызова LLM.
     */
    Optional<AuditLog> findFirstByCaseEntityIdAndEventTypeOrderByIdAsc(Long caseId, String eventType);

    /** Одна полная запись, ограниченная своим кейсом — GET /api/cases/{id}/audit/{eventId}. */
    Optional<AuditLog> findByIdAndCaseEntityId(Long id, Long caseId);
}
