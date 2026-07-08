package uz.caseintel.llm;

import java.util.List;
import java.util.Map;

/**
 * Безопасный срез кейса, единственное, что видит LLM.
 * См. AI_LAYER_ARCHITECTURE.md §3 — главное правило безопасности.
 *
 * LLM НИКОГДА не получает: ФИО, ИНН, номера счетов, телефоны, сырые
 * таблицы транзакций, SQL, доступ к БД. Вместо реальных субъектов —
 * псевдонимы ("Клиент К-1", "Компания С-1"), назначаемые
 * SafeJsonMapper'ом; таблица соответствия псевдоним ↔ реальный субъект
 * живёт только на бэкенде и в LLM не уходит.
 */
public record SafeCaseJson(
        int riskScore,
        String riskLevel,
        ClientProfile client,
        List<Reason> reasons,
        List<SafeEvidence> evidence
) {
    public record ClientProfile(
            String pseudonym,
            String clientType,
            int yearsWithBank,
            int accountsCount,
            boolean hasPreviousAlerts
    ) {}

    public record Reason(
            String factor,
            int contribution,
            String detail
    ) {}

    public record SafeEvidence(
            String type,
            String title,
            Map<String, Object> aggregates
    ) {}
}
