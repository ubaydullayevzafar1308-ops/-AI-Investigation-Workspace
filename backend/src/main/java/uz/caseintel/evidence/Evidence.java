package uz.caseintel.evidence;

import java.util.Map;

/**
 * Единица доказательства в пакете, который видит аналитик и который
 * попадает в Risk Engine (⑤). См. ARCHITECTURE.md §8.
 *
 * weight — вклад в Risk Score; ТОЛЬКО у type=rule_hit weight ненулевой
 * (это уже посчитанный Rule.weight() из сработавшего правила). Остальные
 * типы (relation, previous_alert, anomaly, shared_device, suspicious_tx)
 * информационные — weight=0, они не пересчитывают риск сами по себе
 * (это уже отражено весами соответствующих правил в rule_hit), а дают
 * аналитику и LLM контекст для объяснения "почему".
 */
public record Evidence(
        String type,        // rule_hit | relation | suspicious_tx | previous_alert | anomaly | shared_device
        String title,       // "Rule R03: Circular flow"
        int weight,         // вклад (0 — если информационное)
        Map<String, Object> details
) {
    public static final String TYPE_RULE_HIT = "rule_hit";
    public static final String TYPE_RELATION = "relation";
    public static final String TYPE_SUSPICIOUS_TX = "suspicious_tx";
    public static final String TYPE_PREVIOUS_ALERT = "previous_alert";
    public static final String TYPE_ANOMALY = "anomaly";
    public static final String TYPE_SHARED_DEVICE = "shared_device";
}
