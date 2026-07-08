package uz.caseintel.api;

import java.util.List;
import uz.caseintel.entity.FeatureFlag;
import uz.caseintel.featureflags.FeatureFlagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/modules — список модулей платформы (AML/Fraud/Credit/KYC/
 * Compliance) с их статусом вкл/выкл. См. ARCHITECTURE.md §13, §14.
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private final FeatureFlagService featureFlagService;

    public ModuleController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    public List<FeatureFlag> list() {
        return featureFlagService.allModules();
    }
}
