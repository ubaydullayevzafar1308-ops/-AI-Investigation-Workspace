package uz.caseintel.llm;

import uz.caseintel.llm.providers.ClaudeAdapter;
import uz.caseintel.llm.providers.GeminiAdapter;
import uz.caseintel.llm.providers.LocalLlmAdapter;
import uz.caseintel.llm.providers.OpenAiAdapter;
import uz.caseintel.llm.providers.StubLlmAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Выбор LlmAdapter только конфигом (llm.provider в application.yml),
 * без изменения кода. См. AI_LAYER_ARCHITECTURE.md §4.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmAdapter llmAdapter(LlmProperties props) {
        return switch (props.provider()) {
            case "claude" -> new ClaudeAdapter(props);
            case "local" -> new LocalLlmAdapter(props);
            case "openai" -> new OpenAiAdapter(props);
            case "gemini" -> new GeminiAdapter(props);
            case "stub" -> new StubLlmAdapter();
            default -> throw new IllegalStateException("Unknown LLM provider: " + props.provider());
        };
    }
}
