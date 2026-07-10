package uz.caseintel.api;

import java.time.OffsetDateTime;
import uz.caseintel.entity.AuditLog;

/**
 * Полная запись audit_log без обрезки — GET /api/cases/{id}/audit/{eventId}.
 * Не сама entity — AuditLog.caseEntity LAZY, тот же паттерн, что и в
 * CaseSummaryDto/AlertSummaryDto (см. их javadoc).
 */
public record AuditLogDetailDto(
        Long id,
        Long caseId,
        String eventType,
        String rulesVersion,
        Integer riskScore,
        String llmProvider,
        String llmModel,
        String llmPrompt,
        String llmResponse,
        String actor,
        OffsetDateTime createdAt
) {
    public static AuditLogDetailDto from(AuditLog a) {
        return new AuditLogDetailDto(
                a.getId(),
                a.getCaseEntity() != null ? a.getCaseEntity().getId() : null,
                a.getEventType(),
                a.getRulesVersion(),
                a.getRiskScore(),
                a.getLlmProvider(),
                a.getLlmModel(),
                a.getLlmPrompt(),
                a.getLlmResponse(),
                a.getActor(),
                a.getCreatedAt()
        );
    }
}
