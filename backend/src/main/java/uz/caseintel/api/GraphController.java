package uz.caseintel.api;

import uz.caseintel.entity.Case;
import uz.caseintel.graph.GraphDto;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.repository.CaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/cases/{id}/graph — подграф для визуализации.
 * См. ARCHITECTURE.md §14, §15.
 *
 * @Transactional нужен, чтобы c.getClient() (LAZY-связь) не бросил
 * LazyInitializationException вне активной сессии Hibernate — тот же
 * баг, что был найден и исправлен в CaseController.get() (см. его
 * javadoc/CaseSummaryDto для полного объяснения).
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
    @Transactional(readOnly = true)
    public GraphDto getGraph(@PathVariable Long id) {
        Case c = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + id));
        Long clientId = c.getClient().getId();
        return graphEngineService.build(clientId);
    }
}
