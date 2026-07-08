package uz.caseintel.repository;

import java.util.List;
import java.util.Optional;
import uz.caseintel.entity.Case;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<Case, Long> {

    Optional<Case> findByAlertId(Long alertId);

    List<Case> findByStatus(String status);

    /** История прошлых кейсов клиента — "repeat offender" усилитель в Risk Engine (см. §9). */
    List<Case> findByClientId(Long clientId);
}
