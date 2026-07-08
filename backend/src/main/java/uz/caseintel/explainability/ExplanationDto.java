package uz.caseintel.explainability;

import java.util.List;

/**
 * Структурированное объяснение Risk Score — причина → вклад → деталь.
 * См. ARCHITECTURE.md §10. Это то, что видит и аналитик (ExplainabilityPanel
 * на фронте), и LLM (через Safe JSON, ⑦) — LLM НЕ придумывает причины,
 * только переформулирует уже готовый список в связный текст.
 */
public record ExplanationDto(
        int riskScore,
        String riskLevel,
        List<Reason> reasons
) {
    public record Reason(String factor, int contribution, String detail) {}
}
