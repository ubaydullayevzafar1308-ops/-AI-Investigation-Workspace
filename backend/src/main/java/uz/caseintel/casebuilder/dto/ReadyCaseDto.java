package uz.caseintel.casebuilder.dto;

import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.risk.RiskResult;

/**
 * Полностью собранный кейс — то, что видит аналитик после запуска
 * Case Builder. Соответствует "Ready Investigation Case" в схеме
 * пайплайна (ARCHITECTURE.md §1).
 */
public record ReadyCaseDto(
        Long caseId,
        Long alertId,
        Long clientId,
        RiskResult risk,
        EvidenceBundle evidence,
        ExplanationDto explanation,
        String humanExplanation,
        String reportDraft,
        String status
) {}
