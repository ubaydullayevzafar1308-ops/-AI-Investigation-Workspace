package uz.caseintel.repository;

import java.util.Optional;
import uz.caseintel.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByModuleCode(String moduleCode);
}
