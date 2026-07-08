package uz.caseintel.llm.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import uz.caseintel.llm.LlmAdapter;
import uz.caseintel.llm.LlmProperties;
import org.springframework.web.client.RestClient;

/**
 * Основной провайдер для демо. См. AI_LAYER_ARCHITECTURE.md §4.
 *
 * POST https://api.anthropic.com/v1/messages, заголовки x-api-key,
 * anthropic-version: 2023-06-01, тело {model, max_tokens, system,
 * messages:[{role:"user",content}]}, ответ — content[0].text.
 */
public class ClaudeAdapter implements LlmAdapter {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 2000;

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeAdapter(LlmProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .requestFactory(clientHttpRequestFactory(properties.timeoutSeconds()))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory clientHttpRequestFactory(int timeoutSeconds) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return factory;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", MAX_TOKENS,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // Сеть, таймаут, неверный ключ (401), rate limit (429), любой не-2xx —
            // всё оборачивается в единый тип, чтобы LlmService мог применить
            // fallback независимо от конкретной причины (см. §6).
            throw new LlmProviderException("Claude API call failed", e);
        }

        return extractText(rawResponse);
    }

    private String extractText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
            return "";
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse Claude API response", e);
        }
    }

    @Override
    public String providerName() {
        return "claude";
    }

    @Override
    public String modelName() {
        return properties.model();
    }
}
