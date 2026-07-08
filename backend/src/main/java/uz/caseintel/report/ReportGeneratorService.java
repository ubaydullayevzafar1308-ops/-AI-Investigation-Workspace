package uz.caseintel.report;

import java.time.OffsetDateTime;
import uz.caseintel.entity.Case;
import uz.caseintel.entity.Report;
import uz.caseintel.repository.ReportRepository;
import org.springframework.stereotype.Service;

/**
 * Report Generator (⑨ в пайплайне). Сохраняет черновик отчёта,
 * сгенерированный LlmService.generateReport(), в таблицу reports.
 * См. ARCHITECTURE.md §1 (схема пайплайна) и §4 (таблица reports).
 *
 * Сам текст черновика формирует LLM (⑧) — этот сервис только
 * персистирует результат, никакой генерации текста здесь нет.
 */
@Service
public class ReportGeneratorService {

    private final ReportRepository reportRepository;

    public ReportGeneratorService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public Report saveDraft(Case caseEntity, String draftText) {
        Report report = Report.builder()
                .caseEntity(caseEntity)
                .draftText(draftText)
                .generatedAt(OffsetDateTime.now())
                .build();
        return reportRepository.save(report);
    }

    /** PUT /api/cases/{id}/report — правки аналитика поверх черновика. */
    public Report saveFinalEdit(Long caseId, String finalText, String approvedBy) {
        Report report = reportRepository.findByCaseEntityId(caseId)
                .orElseThrow(() -> new IllegalArgumentException("No report found for case " + caseId));
        report.setFinalText(finalText);
        report.setApprovedBy(approvedBy);
        return reportRepository.save(report);
    }
}
