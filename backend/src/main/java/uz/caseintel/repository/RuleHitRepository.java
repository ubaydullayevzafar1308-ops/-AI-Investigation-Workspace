package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.RuleHit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleHitRepository extends JpaRepository<RuleHit, Long> {

    List<RuleHit> findByCaseEntityId(Long caseId);
}
