package uz.caseintel.casebuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import uz.caseintel.audit.AuditService;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.datacollector.DataCollectorService;
import uz.caseintel.entity.Alert;
import uz.caseintel.entity.Case;
import uz.caseintel.entity.Client;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.evidence.EvidenceCollectorService;
import uz.caseintel.explainability.ExplainabilityService;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.graph.GraphDto;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.llm.LlmService;
import uz.caseintel.llm.SafeCaseJson;
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
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Code review item 4 (CaseBuilderService side) и item 5/9: повторный
 * POST /investigate на кейс с битым evidence_json/explanation_json
 * должен пересобрать кейс заново (полный прогон пайплайна), а не упасть
 * с 500; отсутствующий alert -> 404; alert без клиента -> 404.
 */
class CaseBuilderServiceTest {

    private final DataCollectorService dataCollector = mock(DataCollectorService.class);
    private final RuleEngineService ruleEngine = mock(RuleEngineService.class);
    private final GraphEngineService graphEngine = mock(GraphEngineService.class);
    private final EvidenceCollectorService evidenceCollector = mock(EvidenceCollectorService.class);
    private final RiskEngineService riskEngine = mock(RiskEngineService.class);
    private final ExplainabilityService explainability = mock(ExplainabilityService.class);
    private final SafeJsonMapper safeJsonMapper = mock(SafeJsonMapper.class);
    private final LlmService llmService = mock(LlmService.class);
    private final ReportGeneratorService reportGenerator = mock(ReportGeneratorService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final CaseRepository caseRepository = mock(CaseRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final RuleHitRepository ruleHitRepository = mock(RuleHitRepository.class);

    private final CaseBuilderService service = new CaseBuilderService(
            dataCollector, ruleEngine, graphEngine, evidenceCollector, riskEngine, explainability,
            safeJsonMapper, llmService, reportGenerator, audit, alertRepository, caseRepository,
            reportRepository, auditLogRepository, ruleHitRepository,
            new ObjectMapper().registerModule(new JavaTimeModule()));

    private Client client(long id) {
        return Client.builder()
                .id(id)
                .fullName("Тестовый Клиент")
                .inn("12345678901234")
                .registrationDate(LocalDate.of(2020, 1, 1))
                .clientType("individual")
                .build();
    }

    private DossierDto emptyDossier(long clientId) {
        return new DossierDto(clientId, "Тестовый Клиент", "12345678901234", "+998900000000",
                "device-1", "Address 1", LocalDate.of(2020, 1, 1), "individual", false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private void stubFullPipeline(long clientId) {
        when(dataCollector.collect(anyLong())).thenReturn(emptyDossier(clientId));
        when(ruleEngine.runAll(any())).thenReturn(List.of());
        when(graphEngine.build(anyLong())).thenReturn(new GraphDto(List.of(), List.of()));
        when(evidenceCollector.collect(anyLong(), any(), any()))
                .thenReturn(new EvidenceBundle(clientId, List.of(), 0));
        when(riskEngine.score(any())).thenReturn(new RiskResult(10, RiskResult.LEVEL_LOW));
        when(explainability.explain(any(), any())).thenReturn(new ExplanationDto(10, "low", List.of()));
        when(safeJsonMapper.toSafeJson(any(), any(), any(), any())).thenReturn(
                new SafeCaseJson(10, "low",
                        new SafeCaseJson.ClientProfile("К-1", "individual", 1, 0, false),
                        List.of(), List.of()));
        when(llmService.explainRisk(any(), any())).thenReturn("Объяснение риска");
        when(llmService.generateReport(any(), any())).thenReturn("Черновик отчёта");
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleHitRepository.countByCaseEntityId(anyLong())).thenReturn(0L);
    }

    @Test
    void buildCase_withCorruptedEvidenceSnapshot_rebuildsInsteadOfThrowing() {
        Client c = client(3L);
        Alert alert = Alert.builder()
                .id(5L)
                .client(c)
                .status(Alert.STATUS_INVESTIGATING)
                .triggerReason("test")
                .severity(Alert.SEVERITY_HIGH)
                .createdAt(OffsetDateTime.now())
                .build();
        Case existingCase = Case.builder()
                .id(77L)
                .alert(alert)
                .client(c)
                .status(Case.STATUS_OPEN)
                .createdAt(OffsetDateTime.now())
                .evidenceJson("{ not valid json")
                .explanationJson("{ also not valid")
                .build();

        when(alertRepository.findById(5L)).thenReturn(Optional.of(alert));
        when(caseRepository.findByAlertId(5L)).thenReturn(Optional.of(existingCase));
        stubFullPipeline(3L);

        ReadyCaseDto result = service.buildCase(5L);

        assertThat(result).isNotNull();
        assertThat(result.caseId()).isEqualTo(77L);
        assertThat(result.risk().score()).isEqualTo(10);
        // Пересборка должна была реально прогнать пайплайн заново, а не
        // просто восстановить снапшот.
        verify(dataCollector, times(1)).collect(5L);
        verify(ruleHitRepository, times(1)).deleteByCaseEntityId(77L);
    }

    @Test
    void buildCase_withMissingAlert_throws404() {
        when(alertRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> service.buildCase(999L));

        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void buildCase_withAlertMissingClient_throws404() {
        Alert alert = Alert.builder()
                .id(6L)
                .client(null)
                .status(Alert.STATUS_NEW)
                .triggerReason("test")
                .severity(Alert.SEVERITY_LOW)
                .createdAt(OffsetDateTime.now())
                .build();
        when(alertRepository.findById(6L)).thenReturn(Optional.of(alert));

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> service.buildCase(6L));

        assertThat(ex.getStatusCode().value()).isEqualTo(404);
        verify(caseRepository, never()).findByAlertId(anyLong());
    }
}
