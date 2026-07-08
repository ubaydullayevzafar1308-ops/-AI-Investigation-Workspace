package uz.caseintel.risk;

/**
 * Итог оценки риска — Risk Score (0-100) и уровень.
 * См. ARCHITECTURE.md §9.
 */
public record RiskResult(int score, String level) {
    public static final String LEVEL_LOW = "low";
    public static final String LEVEL_MEDIUM = "medium";
    public static final String LEVEL_HIGH = "high";
}
