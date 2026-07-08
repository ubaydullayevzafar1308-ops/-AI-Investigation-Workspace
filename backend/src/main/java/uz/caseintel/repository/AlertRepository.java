package uz.caseintel.repository;

import java.util.List;
import uz.caseintel.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByStatus(String status);

    List<Alert> findBySeverity(String severity);

    /** История прошлых алертов клиента — важно для R-правил "повторный фигурант" и Evidence Collector. */
    List<Alert> findByClientId(Long clientId);
}
