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
 * (например explanationJson до завершения пайплайна), ИЛИ если сохранённый
 * снапшот повреждён/не читается текущими DTO — в этом случае warning
 * объясняет, какое поле не удалось разобрать (см. CaseController.parseJson);
 * запрос при этом всё равно возвращает 200 с частичными данными, а не 500.
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
        ExplanationDto explanation,
        String warning
) {}
