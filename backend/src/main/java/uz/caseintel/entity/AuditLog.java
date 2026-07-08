package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Одна запись полного журнала расследования.
 * Соответствует таблице `audit_log` (V2__audit_log.sql).
 *
 * Каждый шаг пайплайна CaseBuilderService пишет сюда одну строку
 * (см. AuditService.log/logLlm/logDecision в ARCHITECTURE.md §12).
 * Именно эта таблица — главный аргумент для банковского аудита и судей:
 * полный prompt+response каждого вызова LLM здесь, в открытом виде,
 * что позволяет проверить инвариант "LLM не видел реальных ФИО/ИНН"
 * (см. AI_LAYER_ARCHITECTURE.md §3, §7 — тест-инвариант SafeJsonMapper).
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    /**
     * case_created | rules_executed | graph_built | evidence_collected
     * | risk_scored | explained | llm_called | report_generated | decision_made
     */
    public static final String EVENT_CASE_CREATED = "case_created";
    public static final String EVENT_RULES_EXECUTED = "rules_executed";
    public static final String EVENT_GRAPH_BUILT = "graph_built";
    public static final String EVENT_EVIDENCE_COLLECTED = "evidence_collected";
    public static final String EVENT_RISK_SCORED = "risk_scored";
    public static final String EVENT_EXPLAINED = "explained";
    public static final String EVENT_LLM_CALLED = "llm_called";
    public static final String EVENT_REPORT_GENERATED = "report_generated";
    public static final String EVENT_DECISION_MADE = "decision_made";

    public static final String ACTOR_SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id")
    private Case caseEntity;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** Версия набора правил на момент выполнения, см. RuleEngineService.RULES_VERSION */
    @Column(name = "rules_version", length = 20)
    private String rulesVersion;

    @Column(name = "risk_score")
    private Integer riskScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    /** claude | openai | gemini | local */
    @Column(name = "llm_provider", length = 30)
    private String llmProvider;

    @Column(name = "llm_model", length = 60)
    private String llmModel;

    /** Полный prompt (system + user), отправленный в LLM — для аудита и security-теста. */
    @Column(name = "llm_prompt", columnDefinition = "TEXT")
    private String llmPrompt;

    /** Полный ответ LLM. */
    @Column(name = "llm_response", columnDefinition = "TEXT")
    private String llmResponse;

    /** system | имя аналитика */
    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
