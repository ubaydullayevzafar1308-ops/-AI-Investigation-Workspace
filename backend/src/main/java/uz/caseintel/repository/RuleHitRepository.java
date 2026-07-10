package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.RuleHit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RuleHitRepository extends JpaRepository<RuleHit, Long> {

    List<RuleHit> findByCaseEntityId(Long caseId);

    /** Dashboard: сколько раз сработало каждое правило по всем кейсам. */
    @Query("SELECT r.ruleCode AS ruleCode, COUNT(r) AS hitCount FROM RuleHit r GROUP BY r.ruleCode")
    List<RuleCountProjection> countByRuleCode();

    interface RuleCountProjection {
        String getRuleCode();

        long getHitCount();
    }
}
