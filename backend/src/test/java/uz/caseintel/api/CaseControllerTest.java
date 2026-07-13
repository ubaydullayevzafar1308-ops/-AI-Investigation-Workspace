package uz.caseintel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import uz.caseintel.audit.AuditService;
import uz.caseintel.entity.Case;
import uz.caseintel.repository.CaseRepository;
import org.junit.jupiter.api.Test;

/**
 * Code review item 4: битый JSONB-снапшот (dossier_json / evidence_json /
 * explanation_json) больше не роняет GET /api/cases/{id} 500-кой —
 * возвращается CaseDetailDto с частичными данными (null для не
 * распарсившегося поля) и понятным warning вместо этого.
 */
class CaseControllerTest {

    private final CaseRepository caseRepository = mock(CaseRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final CaseController controller =
            new CaseController(caseRepository, audit, new ObjectMapper());

    @Test
    void get_withCorruptedEvidenceJson_returnsPartialDtoWithWarningInsteadOfThrowing() {
        Case caseEntity = Case.builder()
                .id(7L)
                .status(Case.STATUS_OPEN)
                .riskScore(80)
                .riskLevel(Case.RISK_HIGH)
                .createdAt(OffsetDateTime.now())
                .evidenceJson("{ this is not valid json")
                .explanationJson("""
                        {"riskScore":80,"riskLevel":"high","reasons":[]}
                        """)
                .build();
        when(caseRepository.findById(7L)).thenReturn(Optional.of(caseEntity));

        CaseDetailDto result = controller.get(7L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.riskScore()).isEqualTo(80);
        assertThat(result.evidence()).isNull();
        assertThat(result.explanation()).isNotNull();
        assertThat(result.warning()).isNotNull();
        assertThat(result.warning()).contains("EvidenceBundle");
    }

    @Test
    void get_withAllValidJson_returnsNullWarning() {
        Case caseEntity = Case.builder()
                .id(8L)
                .status(Case.STATUS_OPEN)
                .riskScore(10)
                .riskLevel(Case.RISK_LOW)
                .createdAt(OffsetDateTime.now())
                .explanationJson("""
                        {"riskScore":10,"riskLevel":"low","reasons":[]}
                        """)
                .build();
        when(caseRepository.findById(8L)).thenReturn(Optional.of(caseEntity));

        CaseDetailDto result = controller.get(8L);

        assertThat(result.warning()).isNull();
        assertThat(result.dossier()).isNull();
        assertThat(result.evidence()).isNull();
        assertThat(result.explanation()).isNotNull();
    }
}
