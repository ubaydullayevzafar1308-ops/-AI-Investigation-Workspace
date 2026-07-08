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
 * Обязательный адаптер — аргумент "on-premise" для судей: данные не
 * обязаны покидать контур банка. См. AI_LAYER_ARCHITECTURE.md §4.
 *
 * OpenAI-совместимый /v1/chat/completions (Ollama, vLLM -> Llama/Qwen/
 * DeepSeek). baseUrl берётся из llm.base-url (например
 * http://localhost:11434/v1 для Ollama).
 */
public class LocalLlmAdapter implements LlmAdapter {

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalLlmAdapter(LlmProperties properties) {
        this.properties = properties;
        String baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "llm.base-url must be set when llm.provider=local (e.g. http://localhost:11434/v1)");
        }
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
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
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new LlmProviderException("Local LLM (Ollama/vLLM) call failed", e);
        }

        return extractText(rawResponse);
    }

    private String extractText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse local LLM response", e);
        }
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public String modelName() {
        return properties.model();
    }
}
