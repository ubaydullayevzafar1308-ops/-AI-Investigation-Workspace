package uz.caseintel.casebuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import uz.caseintel.audit.AuditService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.datacollector.DataCollectorService;
import uz.caseintel.entity.Alert;
import uz.caseintel.entity.AuditLog;
import uz.caseintel.entity.Case;
import uz.caseintel.entity.RuleHit;
import uz.caseintel.evidence.EvidenceCollectorService;
import uz.caseintel.explainability.ExplainabilityService;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.llm.LlmService;
import uz.caseintel.llm.SafeJsonMapper;
import uz.caseintel.report.ReportGeneratorService;
import uz.caseintel.repository.AlertRepository;
import uz.caseintel.repository.AuditLogRepository;
import uz.caseintel.repository.CaseRepository;
import uz.caseintel.repository.ReportRepository;
import uz.caseintel.repository.RuleHitRepository;
import uz.caseintel.risk.RiskEngineService;
import uz.caseintel.rules.RuleEngineService;
import uz.caseintel.rules.RuleResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Case Builder — центральный компонент. Оркестратор: не содержит
 * бизнес-логики анализа, а запускает пайплайн и передаёт контекст от
 * этапа к этапу. См. ARCHITECTURE.md §5.
 *
 * Строгий порядок пайплайна (каждый компонент — одна задача):
 * Data Collector -> Rule Engine -> Graph Engine -> Evidence Collector
 * -> Risk Engine -> Explainability Engine -> Safe JSON -> AI Adapter
 * -> Report Generator -> Ready Case -> Analyst -> Decision -> Audit Log.
 *
 * ВАЖНОЕ ОТКЛОНЕНИЕ ОТ ПОРЯДКА В СХЕМЕ §1, О КОТОРОМ СТОИТ ЗНАТЬ: Graph
 * Engine (③) формально идёт после Rule Engine (②) в диаграмме, но
 * CycleDetector (часть Graph Engine) реально вызывается ВНУТРИ Data
 * Collector'а (①), ДО Rule Engine — это осознанное решение, принятое
 * на шаге реализации Rule Engine (см. комментарий у DossierDto.moneyCycles
 * и javadoc R03CircularFlowRule): иначе R03 не мог бы остаться чистой
 * функцией DossierDto -> Optional. GraphEngineService.build() (полный
 * граф для фронта, /api/cases/{id}/graph) по-прежнему вызывается отдельно,
 * в естественном месте после Rule Engine, как показано ниже.
 */
@Service
public class CaseBuilderService {

    private final DataCollectorService dataCollector;
    private final RuleEngineService ruleEngine;
    private final GraphEngineService graphEngine;
    private final EvidenceCollectorService evidenceCollector;
    private final RiskEngineService riskEngine;
    private final ExplainabilityService explainability;
    private final SafeJsonMapper safeJsonMapper;
    private final LlmService llmService;
    private final ReportGeneratorService reportGenerator;
    private final AuditService audit;
    private final AlertRepository alertRepository;
    private final CaseRepository caseRepository;
    private final ReportRepository reportRepository;
    private final AuditLogRepository auditLogRepository;
    private final RuleHitRepository ruleHitRepository;
    private final ObjectMapper objectMapper;

    public CaseBuilderService(
            DataCollectorService dataCollector,
            RuleEngineService ruleEngine,
            GraphEngineService graphEngine,
            EvidenceCollectorService evidenceCollector,
            RiskEngineService riskEngine,
            ExplainabilityService explainability,
            SafeJsonMapper safeJsonMapper,
            LlmService llmService,
            ReportGeneratorService reportGenerator,
            AuditService audit,
            AlertRepository alertRepository,
            CaseRepository caseRepository,
            ReportRepository reportRepository,
            AuditLogRepository auditLogRepository,
            RuleHitRepository ruleHitRepository,
            ObjectMapper objectMapper) {
        this.dataCollector = dataCollector;
        this.ruleEngine = ruleEngine;
        this.graphEngine = graphEngine;
        this.evidenceCollector = evidenceCollector;
        this.riskEngine = riskEngine;
        this.explainability = explainability;
        this.safeJsonMapper = safeJsonMapper;
        this.llmService = llmService;
        this.reportGenerator = reportGenerator;
        this.audit = audit;
        this.alertRepository = alertRepository;
        this.caseRepository = caseRepository;
        this.reportRepository = reportRepository;
        this.auditLogRepository = auditLogRepository;
        this.ruleHitRepository = ruleHitRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReadyCaseDto buildCase(Long alertId) {
        var ctx = new CasePipelineContext(alertId);

        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        // Идемпотентность: если для этого алерта уже есть кейс, вернуть его
        var existingCase = caseRepository.findByAlertId(alertId);
        if (existingCase.isPresent()) {
            var caseEntity = existingCase.get();
            ctx.setCaseEntity(caseEntity);
            return mapExistingCaseToDto(caseEntity);
        }

        Case caseEntity = Case.builder()
                .alert(alert)
                .client(alert.getClient())
                .status(Case.STATUS_OPEN)
                .createdAt(OffsetDateTime.now())
                .build();
        caseEntity = caseRepository.save(caseEntity);
        ctx.setCaseEntity(caseEntity);

        // Алерт взят в работу — часть той же транзакции, что и создание
        // кейса: либо кейс создан И алерт investigating, либо ни то ни другое.
        alert.setStatus(Alert.STATUS_INVESTIGATING);
        alertRepository.save(alert);

        // ① Сбор данных (включает вызов CycleDetector заранее — см. javadoc класса)
        ctx.setDossier(dataCollector.collect(alertId));
        audit.log(caseEntity, AuditLog.EVENT_CASE_CREATED, null);

        // ② Правила (+ персистенция в rule_hits — источник правды для
        // причин Risk Score, трассируемый до конкретных транзакций)
        ctx.setRuleHits(ruleEngine.runAll(ctx.getDossier()));
        persistRuleHits(caseEntity, ctx.getRuleHits());
        audit.log(caseEntity, AuditLog.EVENT_RULES_EXECUTED, null);

        // ③ Граф (полный граф для фронта; циклы для R03 уже посчитаны в ①)
        ctx.setGraph(graphEngine.build(ctx.getDossier().clientId()));
        audit.log(caseEntity, AuditLog.EVENT_GRAPH_BUILT, null);

        // ④ Доказательства
        ctx.setEvidence(evidenceCollector.collect(ctx.getDossier().clientId(), ctx.getRuleHits(), ctx.getDossier()));
        audit.log(caseEntity, AuditLog.EVENT_EVIDENCE_COLLECTED, null);

        // ⑤ Риск
        ctx.setRisk(riskEngine.score(ctx.getEvidence()));
        audit.log(caseEntity, AuditLog.EVENT_RISK_SCORED, ctx.getRisk().score());

        // ⑥ Объяснимость
        ctx.setExplanation(explainability.explain(ctx.getRisk(), ctx.getEvidence()));
        audit.log(caseEntity, AuditLog.EVENT_EXPLAINED, ctx.getRisk().score());

        // ⑦ Безопасный JSON  ⑧ LLM  ⑨ Отчёт
        var safeJson = safeJsonMapper.toSafeJson(ctx.getRisk(), ctx.getExplanation(), ctx.getEvidence(), ctx.getDossier());
        var humanExplanation = llmService.explainRisk(caseEntity, safeJson);
        var reportDraft = llmService.generateReport(caseEntity, safeJson);
        reportGenerator.saveDraft(caseEntity, reportDraft);
        audit.log(caseEntity, AuditLog.EVENT_REPORT_GENERATED, ctx.getRisk().score());

        return persistReadyCase(ctx, humanExplanation, reportDraft);
    }

    private ReadyCaseDto persistReadyCase(CasePipelineContext ctx, String humanExplanation, String reportDraft) {
        Case caseEntity = ctx.getCaseEntity();
        caseEntity.setRiskScore(ctx.getRisk().score());
        caseEntity.setRiskLevel(ctx.getRisk().level());
        caseEntity.setDossierJson(toJson(ctx.getDossier()));
        caseEntity.setEvidenceJson(toJson(ctx.getEvidence()));
        caseEntity.setExplanationJson(toJson(ctx.getExplanation()));
        caseRepository.save(caseEntity);

        return new ReadyCaseDto(
                caseEntity.getId(),
                ctx.getAlertId(),
                ctx.getDossier().clientId(),
                caseEntity.getClient().getFullName(),
                ctx.getRisk(),
                ctx.getEvidence(),
                ctx.getExplanation(),
                humanExplanation,
                reportDraft,
                caseEntity.getStatus(),
                caseEntity.getCreatedAt(),
                ctx.getRuleHits().size(),
                ctx.getEvidence().items().size()
        );
    }

    /**
     * Идемпотентный повтор: кейс уже собран — восстанавливаем ReadyCaseDto
     * целиком из персистентных снапшотов, ничего не пересчитывая и не
     * вызывая LLM заново. Черновик отчёта берётся из reports (финальная
     * правка аналитика приоритетнее черновика), humanExplanation — из
     * audit_log (первый llm_called кейса — это ответ explainRisk,
     * см. javadoc финдера в AuditLogRepository).
     */
    private ReadyCaseDto mapExistingCaseToDto(Case caseEntity) {
        String reportDraft = reportRepository.findByCaseEntityId(caseEntity.getId())
                .map(r -> r.getFinalText() != null ? r.getFinalText() : r.getDraftText())
                .orElse("");
        String humanExplanation = auditLogRepository
                .findFirstByCaseEntityIdAndEventTypeOrderByIdAsc(caseEntity.getId(), AuditLog.EVENT_LLM_CALLED)
                .map(AuditLog::getLlmResponse)
                .orElse("");
        try {
            var evidence = objectMapper.readValue(caseEntity.getEvidenceJson(), uz.caseintel.evidence.EvidenceBundle.class);
            var explanation = objectMapper.readValue(caseEntity.getExplanationJson(), uz.caseintel.explainability.ExplanationDto.class);
            var risk = new uz.caseintel.risk.RiskResult(caseEntity.getRiskScore(), caseEntity.getRiskLevel());
            long ruleHitsCount = evidence.items().stream()
                    .filter(e -> uz.caseintel.evidence.Evidence.TYPE_RULE_HIT.equals(e.type()))
                    .count();
            return new ReadyCaseDto(
                    caseEntity.getId(),
                    caseEntity.getAlert().getId(),
                    caseEntity.getClient().getId(),
                    caseEntity.getClient().getFullName(),
                    risk,
                    evidence,
                    explanation,
                    humanExplanation,
                    reportDraft,
                    caseEntity.getStatus(),
                    caseEntity.getCreatedAt(),
                    (int) ruleHitsCount,
                    evidence.items().size()
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to restore persisted case snapshots for case " + caseEntity.getId(), e);
        }
    }

    private void persistRuleHits(Case caseEntity, List<RuleResult> hits) {
        for (RuleResult hit : hits) {
            ruleHitRepository.save(RuleHit.builder()
                    .caseEntity(caseEntity)
                    .ruleCode(hit.code())
                    .ruleName(hit.name())
                    .weight(hit.weight())
                    .evidenceJson(toJson(hit.evidence()))
                    .explanation(hit.explanation())
                    .createdAt(OffsetDateTime.now())
                    .build());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pipeline snapshot for Case entity", e);
        }
    }
}
