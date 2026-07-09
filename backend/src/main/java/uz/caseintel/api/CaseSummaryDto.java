package uz.caseintel.api;

import java.time.OffsetDateTime;
import uz.caseintel.entity.Case;

/**
 * Безопасное для API представление Case — не сама entity.
 *
 * Причины не отдавать Case напрямую:
 *  1. Case.alert и Case.client — LAZY-связи; без активной транзакции
 *     в момент сериализации Jackson бросит LazyInitializationException
 *     (или, в зависимости от конфигурации, бесконтрольно подтянет и
 *     сериализует ВСЮ цепочку связанных сущностей). Здесь явно
 *     разворачиваем только id, не давая Hibernate-прокси попасть в JSON.
 *  2. dossierJson/evidenceJson/explanationJson в Case хранятся как сырые
 *     JSON-СТРОКИ (для JSONB-колонки) — отдавать их пользователю
 *     напрямую означало бы двойную сериализацию (JSON-строка внутри
 *     JSON-ответа). Клиент явно запрашивает разобранные версии через
 *     отдельные эндпоинты (/evidence, /explanation).
 */
public record CaseSummaryDto(
        Long id,
        Long alertId,
        Long clientId,
        int riskScore,
        String riskLevel,
        String status,
        String analystDecision,
        OffsetDateTime createdAt,
        OffsetDateTime closedAt
) {
    public static CaseSummaryDto from(Case c) {
        return new CaseSummaryDto(
                c.getId(),
                c.getAlert() != null ? c.getAlert().getId() : null,
                c.getClient() != null ? c.getClient().getId() : null,
                c.getRiskScore(),
                c.getRiskLevel(),
                c.getStatus(),
                c.getAnalystDecision(),
                c.getCreatedAt(),
                c.getClosedAt()
        );
    }
}
