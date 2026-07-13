package uz.caseintel.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import uz.caseintel.audit.AuditService;
import uz.caseintel.entity.Case;
import uz.caseintel.llm.cache.LlmResponseCache;
import org.junit.jupiter.api.Test;

/**
 * Code review item 2: попадание в LLM-кэш обязано писать в audit_log
 * так же, как реальный вызов — иначе первый llm_called кейса не
 * находится при идемпотентном повторе /investigate, и humanExplanation
 * молча возвращается пустым (см. CaseBuilderService.tryRestoreExistingCase).
 */
class LlmServiceTest {

    private final LlmAdapter adapter = mock(LlmAdapter.class);
    private final LlmResponseCache cache = mock(LlmResponseCache.class);
    private final AuditService audit = mock(AuditService.class);
    private final LlmProperties properties =
            new LlmProperties("claude", "claude-sonnet-4-6", null, null, true, 30);
    private final LlmService llmService =
            new LlmService(adapter, cache, audit, properties, new ObjectMapper());

    @Test
    void explainRisk_onCacheHit_stillWritesAuditLogAndSkipsAdapter() {
        Case caseEntity = Case.builder().id(42L).build();
        when(adapter.providerName()).thenReturn("claude");
        when(adapter.modelName()).thenReturn("claude-sonnet-4-6");
        when(cache.get(eq("claude"), eq("claude-sonnet-4-6"), anyString(), anyString()))
                .thenReturn(Optional.of("Кэшированное объяснение риска"));

        SafeCaseJson safeJson = new SafeCaseJson(
                80, "high",
                new SafeCaseJson.ClientProfile("К-1", "individual", 3, 2, false),
                List.of(), List.of());

        String result = llmService.explainRisk(caseEntity, safeJson);

        assertThat(result).isEqualTo("Кэшированное объяснение риска");
        // Кэш-хит не должен идти в реальный адаптер...
        verify(adapter, never()).complete(anyString(), anyString());
        // ...но обязан быть виден в audit_log как llm_called, с пометкой,
        // что ответ пришёл из кэша.
        verify(audit, times(1)).logLlm(
                eq(caseEntity),
                eq("claude (cached)"),
                eq("claude-sonnet-4-6"),
                anyString(),
                eq("Кэшированное объяснение риска"));
    }

    @Test
    void explainRisk_onCacheMiss_writesAuditLogWithPlainProviderName() {
        Case caseEntity = Case.builder().id(43L).build();
        when(adapter.providerName()).thenReturn("claude");
        when(adapter.modelName()).thenReturn("claude-sonnet-4-6");
        when(cache.get(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(adapter.complete(anyString(), anyString())).thenReturn("Свежий ответ LLM");

        SafeCaseJson safeJson = new SafeCaseJson(
                50, "medium",
                new SafeCaseJson.ClientProfile("К-2", "individual", 1, 1, false),
                List.of(), List.of());

        String result = llmService.explainRisk(caseEntity, safeJson);

        assertThat(result).isEqualTo("Свежий ответ LLM");
        verify(audit, times(1)).logLlm(
                eq(caseEntity), eq("claude"), eq("claude-sonnet-4-6"), anyString(), eq("Свежий ответ LLM"));
    }
}
