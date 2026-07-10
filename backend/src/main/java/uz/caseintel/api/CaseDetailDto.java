package uz.caseintel.api;

import java.time.OffsetDateTime;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;

/**
 * Полное досье кейса для GET /api/cases/{id} — CaseSummaryDto плюс
 * разобранные dossier/evidence/explanation (хранятся в Case как сырые
 * JSON-строки для JSONB-колонок, см. javadoc CaseSummaryDto). Любое из
 * трёх полей может быть null, если соответствующий JSON ещё не сохранён
 * (например explanationJson до завершения пайплайна).
 */
public record CaseDetailDto(
        Long id,
        Long alertId,
        Long clientId,
        int riskScore,
        String riskLevel,
        String status,
        String analystDecision,
        OffsetDateTime createdAt,
        OffsetDateTime closedAt,
        DossierDto dossier,
        EvidenceBundle evidence,
        ExplanationDto explanation
) {}
