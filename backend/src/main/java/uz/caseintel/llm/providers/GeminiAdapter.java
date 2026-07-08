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
 * Опциональный провайдер (см. AI_LAYER_ARCHITECTURE.md §4).
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=...,
 * тело {contents:[{parts:[{text}]}], systemInstruction:{parts:[{text}]}},
 * ответ — candidates[0].content.parts[0].text.
 */
public class GeminiAdapter implements LlmAdapter {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiAdapter(LlmProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
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
                "contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt)))
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/{model}:generateContent?key={key}", properties.model(), properties.apiKey())
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new LlmProviderException("Gemini API call failed", e);
        }

        return extractText(rawResponse);
    }

    private String extractText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse Gemini response", e);
        }
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public String modelName() {
        return properties.model();
    }
}
