package uz.caseintel.casebuilder.dto;

import java.time.OffsetDateTime;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.risk.RiskResult;

/**
 * Полностью собранный кейс — то, что видит аналитик после запуска
 * Case Builder. Соответствует "Ready Investigation Case" в схеме
 * пайплайна (ARCHITECTURE.md §1).
 *
 * Счётчики (ruleHitsCount, evidenceCount) дублируют размеры вложенных
 * коллекций намеренно: списку алертов/кейсов на фронте нужны только
 * цифры, без парсинга полного EvidenceBundle.
 */
public record ReadyCaseDto(
        Long caseId,
        Long alertId,
        Long clientId,
        String clientName,
        RiskResult risk,
        EvidenceBundle evidence,
        ExplanationDto explanation,
        String humanExplanation,
        String reportDraft,
        String status,
        OffsetDateTime createdAt,
        int ruleHitsCount,
        int evidenceCount
) {}
