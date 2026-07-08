package uz.caseintel.api;

import uz.caseintel.entity.Case;
import uz.caseintel.graph.GraphDto;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.repository.CaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/cases/{id}/graph — подграф для визуализации (react-force-graph-2d).
 * См. ARCHITECTURE.md §14, §15.
 */
@RestController
@RequestMapping("/api/cases")
public class GraphController {

    private final CaseRepository caseRepository;
    private final GraphEngineService graphEngineService;

    public GraphController(CaseRepository caseRepository, GraphEngineService graphEngineService) {
        this.caseRepository = caseRepository;
        this.graphEngineService = graphEngineService;
    }

    @GetMapping("/{id}/graph")
    public GraphDto getGraph(@PathVariable Long id) {
        Case c = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + id));
        Long clientId = c.getClient().getId();
        return graphEngineService.build(clientId);
    }
}
