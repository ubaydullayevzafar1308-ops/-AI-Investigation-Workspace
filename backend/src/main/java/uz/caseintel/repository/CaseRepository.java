package uz.caseintel.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import uz.caseintel.entity.Case;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CaseRepository extends JpaRepository<Case, Long> {

    Optional<Case> findByAlertId(Long alertId);

    List<Case> findByStatus(String status);

    /** История прошлых кейсов клиента — "repeat offender" усилитель в Risk Engine (см. §9). */
    List<Case> findByClientId(Long clientId);

    /** Dashboard: кейсов "за сегодня" — createdAt >= начало текущих суток. */
    long countByCreatedAtGreaterThanEqual(OffsetDateTime start);

    /** Dashboard: средний Risk Score по всем кейсам, null если кейсов нет. */
    @Query("SELECT AVG(c.riskScore) FROM Case c")
    Double averageRiskScore();
}
