package uz.caseintel.llm.providers;

/**
 * Единая ошибка для всех сбоев LLM-провайдера (сеть, неверный ключ,
 * таймаут, 429, невалидный ответ). LlmService ловит именно её и
 * применяет fallback-тексты, см. AI_LAYER_ARCHITECTURE.md §6.
 */
public class LlmProviderException extends RuntimeException {
    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmProviderException(String message) {
        super(message);
    }
}
