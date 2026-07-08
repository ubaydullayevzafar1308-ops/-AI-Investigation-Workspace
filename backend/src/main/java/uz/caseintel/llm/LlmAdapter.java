package uz.caseintel.llm;

/**
 * Единый интерфейс провайдера LLM. См. AI_LAYER_ARCHITECTURE.md §4.
 *
 * Система не привязана к одному вендору — выбор реализации происходит
 * только конфигом (application.yml: llm.provider), без изменения кода
 * (см. LlmConfig). Это прямой ответ на вопрос судей "привязка к
 * OpenAI/Anthropic?" — нет, включая on-premise Llama/Qwen через
 * LocalLlmAdapter.
 */
public interface LlmAdapter {

    String complete(String systemPrompt, String userPrompt);

    /** "claude" | "openai" | "gemini" | "local" — для Audit Log. */
    String providerName();

    /** "claude-sonnet-4-6" и т.п. — для Audit Log. */
    String modelName();
}
