package uz.caseintel.casebuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import uz.caseintel.audit.AuditService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.datacollector.DataCollectorService;
import uz.caseintel.entity.Alert;
import uz.caseintel.entity.AuditLog;
import uz.caseintel.entity.Case;
import uz.caseintel.evidence.EvidenceCollectorService;
import uz.caseintel.explainability.ExplainabilityService;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.llm.LlmService;
import uz.caseintel.llm.SafeJsonMapper;
import uz.caseintel.report.ReportGeneratorService;
import uz.caseintel.repository.AlertRepository;
import uz.caseintel.repository.CaseRepository;
import uz.caseintel.risk.RiskEngineService;
import uz.caseintel.rules.RuleEngineService;
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
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReadyCaseDto buildCase(Long alertId) {
        var ctx = new CasePipelineContext(alertId);

        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        Case caseEntity = Case.builder()
                .alert(alert)
                .client(alert.getClient())
                .status(Case.STATUS_OPEN)
                .createdAt(OffsetDateTime.now())
                .build();
        caseEntity = caseRepository.save(caseEntity);
        ctx.setCaseEntity(caseEntity);

        // ① Сбор данных (включает вызов CycleDetector заранее — см. javadoc класса)
        ctx.setDossier(dataCollector.collect(alertId));
        audit.log(caseEntity, AuditLog.EVENT_CASE_CREATED, null);

        // ② Правила
        ctx.setRuleHits(ruleEngine.runAll(ctx.getDossier()));
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
                ctx.getRisk(),
                ctx.getEvidence(),
                ctx.getExplanation(),
                humanExplanation,
                reportDraft,
                caseEntity.getStatus()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pipeline snapshot for Case entity", e);
        }
    }
}
