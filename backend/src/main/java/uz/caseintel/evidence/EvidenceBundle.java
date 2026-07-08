package uz.caseintel.evidence;

import java.util.List;

/**
 * Полный пакет доказательств одного расследования — вход для Risk
 * Engine (⑤) и, вместе с ExplanationDto, для Safe JSON (⑦).
 * См. ARCHITECTURE.md §8.
 */
public record EvidenceBundle(
        Long clientId,
        List<Evidence> items,
        int totalRuleWeight
) {}
