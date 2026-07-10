package uz.caseintel.api;

import java.time.OffsetDateTime;
import uz.caseintel.entity.Alert;

/**
 * Безопасное для API представление Alert — не сама entity.
 *
 * Alert.transaction и Alert.client — LAZY-связи; при сериализации entity
 * напрямую Jackson натыкается на неинициализированный Hibernate-прокси
 * вне сессии (open-in-view: false) и падает с
 * HttpMessageNotWritableException ("could not initialize proxy - no
 * Session"). Здесь, как и в CaseSummaryDto, разворачиваем связи только
 * до id.
 */
public record AlertSummaryDto(
        Long id,
        Long transactionId,
        Long clientId,
        String triggerReason,
        String severity,
        String status,
        OffsetDateTime createdAt
) {
    public static AlertSummaryDto from(Alert a) {
        return new AlertSummaryDto(
                a.getId(),
                a.getTransaction() != null ? a.getTransaction().getId() : null,
                a.getClient() != null ? a.getClient().getId() : null,
                a.getTriggerReason(),
                a.getSeverity(),
                a.getStatus(),
                a.getCreatedAt()
        );
    }
}
