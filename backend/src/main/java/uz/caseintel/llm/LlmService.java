package uz.caseintel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import uz.caseintel.audit.AuditService;
import uz.caseintel.entity.Case;
import uz.caseintel.llm.cache.LlmResponseCache;
import uz.caseintel.llm.providers.LlmProviderException;
import org.springframework.stereotype.Service;

/**
 * Фасад LLM-слоя — 3 функции + кэш + audit + fallback.
 * См. AI_LAYER_ARCHITECTURE.md §2, §9.
 *
 * Каждый метод: собрать промпт -> кэш -> (адаптер при промахе) ->
 * audit.logLlm -> вернуть текст (или fallback из §6).
 *
 * Роль LLM — только язык, не анализ (см. §1). Ни один из трёх методов
 * не передаёт LLM ничего, кроме SafeCaseJson — банковские данные сюда
 * физически не попадают (см. SafeJsonMapper).
 */
@Service
public class LlmService {

    private final LlmAdapter adapter;
    private final LlmResponseCache cache;
    private final AuditService audit;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmService(LlmAdapter adapter, LlmResponseCache cache, AuditService audit, LlmProperties properties,
                       ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.cache = cache;
        this.audit = audit;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** explainRisk — используется автоматически при сборке кейса (Case Builder). */
    public String explainRisk(Case caseEntity, SafeCaseJson safeJson) {
        String userPrompt = Prompts.EXPLAIN_RISK_TEMPLATE.formatted(toJson(safeJson));
        return callWithFallback(caseEntity, userPrompt,
                "Автоматическое объяснение временно недоступно. См. структурированные причины выше.");
    }

    /** generateReport — используется автоматически при сборке кейса (Case Builder). */
    public String generateReport(Case caseEntity, SafeCaseJson safeJson) {
        String userPrompt = Prompts.GENERATE_REPORT_TEMPLATE.formatted(toJson(safeJson));
        return callWithFallback(caseEntity, userPrompt,
                "Черновик отчёта будет сгенерирован позже.");
    }

    /** answerQuestion — по запросу из чата на фронте (ChatController). */
    public String answerQuestion(Case caseEntity, SafeCaseJson safeJson, String question) {
        String userPrompt = Prompts.ANSWER_QUESTION_TEMPLATE.formatted(toJson(safeJson), question);
        return callWithFallback(caseEntity, userPrompt,
                "Не удалось обработать вопрос — попробуйте ещё раз позже.");
    }

    /**
     * Общая логика вызова: кэш -> адаптер -> audit -> fallback при ошибке.
     *
     * Правила устойчивости к ошибкам (см. §6):
     * 1. Любая ошибка LLM (сеть, ключ, таймаут, 429) -> лог + fallback-текст.
     * 2. Кейс при этом собирается полноценно — score/evidence/explanation
     *    из детерминированных движков не зависят от LLM вообще (вызывающий
     *    код, CaseBuilderService, продолжает пайплайн независимо от того,
     *    что вернул этот метод).
     * 3. Таймаут — настраивается в LlmProperties.timeoutSeconds, один
     *    повтор при сетевой ошибке (см. callAdapterWithOneRetry ниже).
     */
    private String callWithFallback(Case caseEntity, String userPrompt, String fallbackText) {
        String provider = adapter.providerName();
        String model = adapter.modelName();

        if (properties.cacheEnabled()) {
            var cached = cache.get(provider, model, Prompts.SYSTEM_PROMPT, userPrompt);
            if (cached.isPresent()) {
                // Попадание в кэш — это тоже "вызов LLM" с точки зрения
                // аудита: без этой записи первый llm_called кейса при
                // повторном /investigate не находится (см.
                // AuditLogRepository.findFirstByCaseEntityIdAndEventTypeOrderByIdAsc
                // и CaseBuilderService.tryRestoreExistingCase), и
                // humanExplanation молча возвращается пустым. Провайдер
                // помечается суффиксом, чтобы в audit_log было видно, что
                // ответ пришёл из файлового кэша, а не от реального вызова.
                audit.logLlm(caseEntity, provider + " (cached)", model,
                        Prompts.SYSTEM_PROMPT + "\n\n" + userPrompt, cached.get());
                return cached.get();
            }
        }

        String response;
        try {
            response = callAdapterWithOneRetry(userPrompt);
        } catch (LlmProviderException e) {
            audit.logLlm(caseEntity, provider, model, Prompts.SYSTEM_PROMPT + "\n\n" + userPrompt,
                    "[ERROR] " + e.getMessage());
            return fallbackText;
        }

        audit.logLlm(caseEntity, provider, model, Prompts.SYSTEM_PROMPT + "\n\n" + userPrompt, response);

        if (properties.cacheEnabled()) {
            cache.put(provider, model, Prompts.SYSTEM_PROMPT, userPrompt, response);
        }

        return response;
    }

    private String callAdapterWithOneRetry(String userPrompt) {
        try {
            return adapter.complete(Prompts.SYSTEM_PROMPT, userPrompt);
        } catch (LlmProviderException firstAttemptFailure) {
            return adapter.complete(Prompts.SYSTEM_PROMPT, userPrompt);
        }
    }

    private String toJson(SafeCaseJson safeJson) {
        try {
            return objectMapper.writeValueAsString(safeJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SafeCaseJson", e);
        }
    }
}
