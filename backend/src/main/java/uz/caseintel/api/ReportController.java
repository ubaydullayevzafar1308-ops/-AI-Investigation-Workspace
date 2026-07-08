package uz.caseintel.api;

import uz.caseintel.entity.Report;
import uz.caseintel.report.ReportGeneratorService;
import uz.caseintel.repository.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/cases/{id}/report — черновик; PUT — правки аналитика.
 * См. ARCHITECTURE.md §14.
 */
@RestController
@RequestMapping("/api/cases")
public class ReportController {

    private final ReportRepository reportRepository;
    private final ReportGeneratorService reportGeneratorService;

    public ReportController(ReportRepository reportRepository, ReportGeneratorService reportGeneratorService) {
        this.reportRepository = reportRepository;
        this.reportGeneratorService = reportGeneratorService;
    }

    @GetMapping("/{id}/report")
    public Report getReport(@PathVariable Long id) {
        return reportRepository.findByCaseEntityId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report for case: " + id));
    }

    public record ReportEditRequest(String finalText, String approvedBy) {}

    @PutMapping("/{id}/report")
    public Report updateReport(@PathVariable Long id, @RequestBody ReportEditRequest request) {
        return reportGeneratorService.saveFinalEdit(id, request.finalText(), request.approvedBy());
    }
}
