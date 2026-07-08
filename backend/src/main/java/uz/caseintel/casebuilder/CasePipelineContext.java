package uz.caseintel.casebuilder;

import java.util.List;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.entity.Case;
import uz.caseintel.evidence.EvidenceBundle;
import uz.caseintel.explainability.ExplanationDto;
import uz.caseintel.graph.GraphDto;
import uz.caseintel.risk.RiskResult;
import uz.caseintel.rules.RuleResult;

/**
 * Контекст, идущий по пайплайну Case Builder — каждый этап (①-⑨)
 * дописывает в него свою часть. См. ARCHITECTURE.md §5.
 *
 * Намеренно мутабельный (не record) — это рабочий объект одного
 * прогона пайплайна, а не value object для передачи между слоями;
 * CaseBuilderService — единственное место, которое его создаёт и
 * читает целиком.
 */
public class CasePipelineContext {

    private final Long alertId;
    private Case caseEntity;
    private DossierDto dossier;
    private List<RuleResult> ruleHits;
    private GraphDto graph;
    private EvidenceBundle evidence;
    private RiskResult risk;
    private ExplanationDto explanation;

    public CasePipelineContext(Long alertId) {
        this.alertId = alertId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public Long getCaseId() {
        return caseEntity != null ? caseEntity.getId() : null;
    }

    public Case getCaseEntity() {
        return caseEntity;
    }

    public void setCaseEntity(Case caseEntity) {
        this.caseEntity = caseEntity;
    }

    public DossierDto getDossier() {
        return dossier;
    }

    public void setDossier(DossierDto dossier) {
        this.dossier = dossier;
    }

    public List<RuleResult> getRuleHits() {
        return ruleHits;
    }

    public void setRuleHits(List<RuleResult> ruleHits) {
        this.ruleHits = ruleHits;
    }

    public GraphDto getGraph() {
        return graph;
    }

    public void setGraph(GraphDto graph) {
        this.graph = graph;
    }

    public EvidenceBundle getEvidence() {
        return evidence;
    }

    public void setEvidence(EvidenceBundle evidence) {
        this.evidence = evidence;
    }

    public RiskResult getRisk() {
        return risk;
    }

    public void setRisk(RiskResult risk) {
        this.risk = risk;
    }

    public ExplanationDto getExplanation() {
        return explanation;
    }

    public void setExplanation(ExplanationDto explanation) {
        this.explanation = explanation;
    }
}
