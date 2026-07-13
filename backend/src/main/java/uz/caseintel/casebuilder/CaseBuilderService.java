package uz.caseintel.casebuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import uz.caseintel.audit.AuditService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.datacollector.DataCollectorService;
import uz.caseintel.entity.Alert;
import uz.caseintel.entity.AuditLog;
import uz.caseintel.entity.Case;
import uz.caseintel.entity.RuleHit;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.evidence.EvidenceCollectorService;
import uz.caseintel.explainability.ExplainabilityService;
import uz.caseintel.explainability.ExplanationDto;
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
import uz.caseintel.risk.RiskResult;
import uz.caseintel.rules.RuleEngineService;
import uz.caseintel.rules.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private static final Logger log = LoggerFactory.getLogger(CaseBuilderService.class);

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

    /**
     * ГОНКА КОНКУРЕНТНЫХ ВЫЗОВОВ: если два запроса POST /investigate для
     * одного alertId проходят проверку идемпотентности одновременно, оба
     * попадут в ветку создания нового Case ниже. caseRepository.save()
     * с GenerationType.IDENTITY форсирует немедленный INSERT (не может
     * быть отложен до commit), поэтому UNIQUE-констрейнт cases.alert_id
     * ловит проигравшего сразу здесь, ДО дорогого пайплайна — исключение
     * (DataIntegrityViolationException) улетает наверх необработанным.
     * Ловить и ретраить его нужно СНАРУЖИ (AlertController), а не в этом
     * методе: после провала flush текущая транзакция обречена на rollback,
     * и повторное чтение должно происходить в новой, чистой транзакции —
     * что и даёт вызов buildCase() ещё раз через Spring-прокси контроллера.
     */
    @Transactional
    public ReadyCaseDto buildCase(Long alertId) {
        var ctx = new CasePipelineContext(alertId);

        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found: " + alertId));

        if (alert.getClient() == null) {
            // Весь пайплайн (Data Collector и далее) требует clientId —
            // без клиента алерт физически нельзя расследовать. Явный 404
            // здесь вместо NPE где-то в середине пайплайна.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Alert " + alertId + " has no associated client and cannot be investigated");
        }

        // Идемпотентность: если для этого алерта уже есть кейс, вернуть его
        var existingCase = caseRepository.findByAlertId(alertId);
        if (existingCase.isPresent()) {
            var caseEntity = existingCase.get();
            selfHealAlertStatus(alert, caseEntity);
            ctx.setCaseEntity(caseEntity);
            return tryRestoreExistingCase(caseEntity)
                    .orElseGet(() -> {
                        log.warn("Rebuilding case {} from scratch — persisted snapshot was unreadable", caseEntity.getId());
                        return runPipeline(ctx, caseEntity);
                    });
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

        return runPipeline(ctx, caseEntity);
    }

    /**
     * Полный прогон пайплайна ①-⑨ на уже существующей (persisted) записи
     * Case. Используется и для свежего кейса, и для пересборки поверх
     * существующего кейса, чей JSON-снапшот оказался нечитаемым (см.
     * javadoc tryRestoreExistingCase) — во втором случае просто
     * перезаписывает riskScore/riskLevel/dossierJson/evidenceJson/
     * explanationJson той же строки, не трогая status/analystDecision/
     * closedAt (решение аналитика, если оно уже было, не переоткрывается).
     */
    private ReadyCaseDto runPipeline(CasePipelineContext ctx, Case caseEntity) {
        Long alertId = ctx.getAlertId();

        // ① Сбор данных (включает вызов CycleDetector заранее — см. javadoc класса)
        ctx.setDossier(dataCollector.collect(alertId));
        audit.log(caseEntity, AuditLog.EVENT_CASE_CREATED, null);

        // ② Правила (+ персистенция в rule_hits — единственный источник
        // правды для ruleHitsCount и причин Risk Score; persistRuleHits
        // сначала удаляет старые хиты этого кейса, поэтому безопасна и
        // при пересборке).
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
                clientNameOrNull(caseEntity),
                ctx.getRisk(),
                ctx.getEvidence(),
                ctx.getExplanation(),
                humanExplanation,
                reportDraft,
                caseEntity.getStatus(),
                caseEntity.getCreatedAt(),
                (int) ruleHitRepository.countByCaseEntityId(caseEntity.getId()),
                ctx.getEvidence().items().size()
        );
    }

    /**
     * Идемпотентный повтор: пытается восстановить ReadyCaseDto целиком из
     * персистентных снапшотов, ничего не пересчитывая и не вызывая LLM
     * заново. Черновик отчёта берётся из reports (финальная правка
     * аналитика приоритетнее черновика), humanExplanation — из audit_log
     * (первый llm_called кейса — это ответ explainRisk, см. javadoc
     * финдера в AuditLogRepository; попадания в LLM-кэш теперь тоже
     * пишут llm_called — см. LlmService — поэтому эта запись есть
     * практически всегда).
     *
     * Возвращает Optional.empty(), если evidence_json/explanation_json
     * повреждены (не читаются текущими DTO) — в этом случае buildCase()
     * пересобирает кейс заново вместо 500.
     */
    private Optional<ReadyCaseDto> tryRestoreExistingCase(Case caseEntity) {
        EvidenceBundle evidence;
        ExplanationDto explanation;
        try {
            evidence = objectMapper.readValue(caseEntity.getEvidenceJson(), EvidenceBundle.class);
            explanation = objectMapper.readValue(caseEntity.getExplanationJson(), ExplanationDto.class);
        } catch (Exception e) {
            log.error("Failed to parse persisted evidence/explanation snapshot for case {}", caseEntity.getId(), e);
            return Optional.empty();
        }

        String reportDraft = reportRepository.findByCaseEntityId(caseEntity.getId())
                .map(r -> r.getFinalText() != null ? r.getFinalText() : r.getDraftText())
                .orElse("");
        String humanExplanation = auditLogRepository
                .findFirstByCaseEntityIdAndEventTypeOrderByIdAsc(caseEntity.getId(), AuditLog.EVENT_LLM_CALLED)
                .map(AuditLog::getLlmResponse)
                .orElseGet(() -> {
                    log.warn("No llm_called audit row found for case {} — humanExplanation will be empty", caseEntity.getId());
                    return "";
                });

        var risk = new RiskResult(caseEntity.getRiskScore(), caseEntity.getRiskLevel());
        long ruleHitsCount = ruleHitRepository.countByCaseEntityId(caseEntity.getId());

        return Optional.of(new ReadyCaseDto(
                caseEntity.getId(),
                caseEntity.getAlert().getId(),
                clientIdOrNull(caseEntity),
                clientNameOrNull(caseEntity),
                risk,
                evidence,
                explanation,
                humanExplanation,
                reportDraft,
                caseEntity.getStatus(),
                caseEntity.getCreatedAt(),
                (int) ruleHitsCount,
                evidence.items().size()
        ));
    }

    /**
     * Повторный /investigate на уже собранный, но всё ещё открытый кейс
     * должен восстанавливать статус алерта на investigating, если он
     * рассинхронизировался (например статус был сброшен вручную) —
     * иначе список алертов показывает кейс с активным расследованием
     * как "new".
     */
    private void selfHealAlertStatus(Alert alert, Case caseEntity) {
        if (Case.STATUS_OPEN.equals(caseEntity.getStatus()) && !Alert.STATUS_INVESTIGATING.equals(alert.getStatus())) {
            alert.setStatus(Alert.STATUS_INVESTIGATING);
            alertRepository.save(alert);
        }
    }

    private void persistRuleHits(Case caseEntity, List<RuleResult> hits) {
        // Удаляем предыдущие хиты этого кейса перед вставкой новых — без
        // этого пересборка (см. tryRestoreExistingCase) задвоила бы
        // rule_hits и испортила бы ruleHitsCount/дашборд.
        ruleHitRepository.deleteByCaseEntityId(caseEntity.getId());
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

    private String clientNameOrNull(Case caseEntity) {
        return caseEntity.getClient() != null ? caseEntity.getClient().getFullName() : null;
    }

    private Long clientIdOrNull(Case caseEntity) {
        return caseEntity.getClient() != null ? caseEntity.getClient().getId() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pipeline snapshot for Case entity", e);
        }
    }
}
