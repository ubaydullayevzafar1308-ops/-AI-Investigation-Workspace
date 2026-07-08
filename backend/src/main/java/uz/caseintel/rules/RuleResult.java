package uz.caseintel.rules;

import java.util.Map;

/**
 * Результат срабатывания одного правила Rule Engine (②).
 *
 * evidence — сырые факты (id транзакций, суммы, даты), которые попадут
 * в RuleHit.evidenceJson и далее в Evidence Collector (④). explanation —
 * уже готовая фраза на русском (не LLM!) — Rule Engine формулирует её
 * сам, детерминированно, через String.formatted() с конкретными цифрами.
 * LLM (⑧) позже только переформулирует набор таких фраз в связный текст,
 * не выдумывая новых.
 */
public record RuleResult(
        String code,
        String name,
        int weight,
        Map<String, Object> evidence,
        String explanation
) {}
