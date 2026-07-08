package uz.caseintel.audit;

import java.time.OffsetDateTime;
import uz.caseintel.entity.AuditLog;
import uz.caseintel.entity.Case;
import uz.caseintel.repository.AuditLogRepository;
import uz.caseintel.rules.RuleEngineService;
import org.springframework.stereotype.Service;

/**
 * Audit Log (см. ARCHITECTURE.md §12). Каждый шаг пайплайна
 * CaseBuilderService пишет сюда одну строку. Это главный аргумент для
 * банковского аудита и судей: полный prompt+response каждого вызова
 * LLM здесь, в открытом виде, что позволяет проверить инвариант "LLM
 * не видел реальных ФИО/ИНН" (см. AI_LAYER_ARCHITECTURE.md §3, §7).
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Обычное событие пайплайна (case_created, rules_executed, graph_built, ...). */
    public void log(Case caseEntity, String eventType, Integer riskScore) {
        AuditLog entry = AuditLog.builder()
                .caseEntity(caseEntity)
                .eventType(eventType)
                .rulesVersion(RuleEngineService.RULES_VERSION)
                .riskScore(riskScore)
                .actor(AuditLog.ACTOR_SYSTEM)
                .createdAt(OffsetDateTime.now())
                .build();
        repository.save(entry);
    }

    /**
     * Вызов LLM — полный prompt и полный response записываются целиком.
     * Это одновременно и требование банковского аудита, и способ
     * проверить безопасность: в записанном промпте не должно быть
     * реальных имён (см. тест-инвариант SafeJsonMapper).
     */
    public void logLlm(Case caseEntity, String provider, String model, String fullPrompt, String fullResponse) {
        AuditLog entry = AuditLog.builder()
                .caseEntity(caseEntity)
                .eventType(AuditLog.EVENT_LLM_CALLED)
                .rulesVersion(RuleEngineService.RULES_VERSION)
                .llmProvider(provider)
                .llmModel(model)
                .llmPrompt(fullPrompt)
                .llmResponse(fullResponse)
                .actor(AuditLog.ACTOR_SYSTEM)
                .createdAt(OffsetDateTime.now())
                .build();
        repository.save(entry);
    }

    /**
     * Финальное решение аналитика — единственная запись, где actor не
     * "system". Сам текст решения (approved/rejected/escalated + комментарий)
     * хранится в Case.analystDecision самим CaseController'ом — здесь
     * фиксируется только факт "кто и когда принял решение" для аудита.
     */
    public void logDecision(Case caseEntity, String analystName) {
        AuditLog entry = AuditLog.builder()
                .caseEntity(caseEntity)
                .eventType(AuditLog.EVENT_DECISION_MADE)
                .rulesVersion(RuleEngineService.RULES_VERSION)
                .actor(analystName)
                .createdAt(OffsetDateTime.now())
                .build();
        repository.save(entry);
    }
}
