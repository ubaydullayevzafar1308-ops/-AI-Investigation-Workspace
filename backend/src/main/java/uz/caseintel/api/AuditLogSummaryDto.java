package uz.caseintel.api;

import java.time.OffsetDateTime;
import uz.caseintel.entity.AuditLog;

/**
 * Запись audit_log для списка GET /api/cases/{id}/audit — llmPrompt и
 * llmResponse обрезаны до 200 символов, чтобы список не тянул полные
 * тексты промптов на каждую запись. Полная запись — GET
 * /api/cases/{id}/audit/{eventId}.
 */
public record AuditLogSummaryDto(
        Long id,
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
    private static final int MAX_LEN = 200;

    public static AuditLogSummaryDto from(AuditLog a) {
        return new AuditLogSummaryDto(
                a.getId(),
                a.getEventType(),
                a.getRulesVersion(),
                a.getRiskScore(),
                a.getLlmProvider(),
                a.getLlmModel(),
                truncate(a.getLlmPrompt()),
                truncate(a.getLlmResponse()),
                a.getActor(),
                a.getCreatedAt()
        );
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_LEN) {
            return text;
        }
        return text.substring(0, MAX_LEN) + "…";
    }
}
