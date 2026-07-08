package uz.caseintel.featureflags;

import java.util.List;
import uz.caseintel.entity.FeatureFlag;
import uz.caseintel.repository.FeatureFlagRepository;
import org.springframework.stereotype.Service;

/**
 * Модульность платформы — вкл/выкл модулей (AML/Fraud/Credit/KYC/
 * Compliance). См. ARCHITECTURE.md §13. Экран Modules.jsx на фронте
 * показывает судьям, что MVP (AML) — это один модуль большой платформы.
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository repository;

    public FeatureFlagService(FeatureFlagRepository repository) {
        this.repository = repository;
    }

    public boolean isEnabled(String moduleCode) {
        return repository.findByModuleCode(moduleCode)
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    public List<FeatureFlag> allModules() {
        return repository.findAll();
    }
}
