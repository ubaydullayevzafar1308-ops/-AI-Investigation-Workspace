package uz.caseintel.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфиг LLM-слоя, читается из application.yml (префикс llm.*, см.
 * AI_LAYER_ARCHITECTURE.md §4). Уже объявлен в application.yml
 * (см. Step 1), здесь просто типизированный биндинг.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String provider,        // claude | openai | gemini | local
        String model,
        String baseUrl,         // для local: http://localhost:11434/v1
        String apiKey,
        boolean cacheEnabled,
        int timeoutSeconds
) {}
