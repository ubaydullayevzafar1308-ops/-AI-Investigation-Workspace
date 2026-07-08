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
 * POST https://api.openai.com/v1/chat/completions, Bearer-токен,
 * тело {model, messages:[{role, content}, ...]}, ответ —
 * choices[0].message.content.
 */
public class OpenAiAdapter implements LlmAdapter {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiAdapter(LlmProperties properties) {
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
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new LlmProviderException("OpenAI API call failed", e);
        }

        return extractText(rawResponse);
    }

    private String extractText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse OpenAI response", e);
        }
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public String modelName() {
        return properties.model();
    }
}
