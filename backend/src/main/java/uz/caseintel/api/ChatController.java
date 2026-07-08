package uz.caseintel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.entity.Case;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.llm.LlmService;
import uz.caseintel.llm.SafeJsonMapper;
import uz.caseintel.repository.CaseRepository;
import uz.caseintel.risk.RiskResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /api/cases/{id}/chat — вопрос аналитика по фактам кейса.
 * См. ARCHITECTURE.md §14, AI_LAYER_ARCHITECTURE.md §2 (функция 3).
 *
 * Пересобирает SafeCaseJson из снапшотов, сохранённых в Case при сборке
 * (dossierJson/evidenceJson/explanationJson) — не обращается к живым
 * данным клиента заново, чат отвечает строго по тому состоянию кейса,
 * которое видел аналитик при открытии (воспроизводимость для аудита).
 */
@RestController
@RequestMapping("/api/cases")
public class ChatController {

    private final CaseRepository caseRepository;
    private final LlmService llmService;
    private final SafeJsonMapper safeJsonMapper;
    private final ObjectMapper objectMapper;

    public ChatController(CaseRepository caseRepository, LlmService llmService,
                           SafeJsonMapper safeJsonMapper, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.llmService = llmService;
        this.safeJsonMapper = safeJsonMapper;
        this.objectMapper = objectMapper;
    }

    public record ChatRequest(String question) {}

    public record ChatResponse(String answer) {}

    @PostMapping("/{id}/chat")
    public ChatResponse chat(@PathVariable Long id, @RequestBody ChatRequest request) {
        Case c = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + id));

        DossierDto dossier = parseJson(c.getDossierJson(), DossierDto.class);
        EvidenceBundle evidence = parseJson(c.getEvidenceJson(), EvidenceBundle.class);
        ExplanationDto explanation = parseJson(c.getExplanationJson(), ExplanationDto.class);
        RiskResult risk = new RiskResult(c.getRiskScore(), c.getRiskLevel());

        var safeJson = safeJsonMapper.toSafeJson(risk, explanation, evidence, dossier);
        String answer = llmService.answerQuestion(c, safeJson, request.question());

        return new ChatResponse(answer);
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Case has no stored snapshot yet — was it fully built?");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored snapshot for case chat", e);
        }
    }
}
