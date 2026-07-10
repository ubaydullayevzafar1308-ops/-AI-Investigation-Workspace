package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByStatus(String status);

    List<Alert> findBySeverity(String severity);

    /** История прошлых алертов клиента — важно для R-правил "повторный фигурант" и Evidence Collector. */
    List<Alert> findByClientId(Long clientId);

    /** GET /api/alerts: status и severity — опциональные фильтры, комбинируются через AND. */
    @Query("SELECT a FROM Alert a "
            + "WHERE (:status IS NULL OR a.status = :status) "
            + "AND (:severity IS NULL OR a.severity = :severity)")
    Page<Alert> search(@Param("status") String status, @Param("severity") String severity, Pageable pageable);
}
