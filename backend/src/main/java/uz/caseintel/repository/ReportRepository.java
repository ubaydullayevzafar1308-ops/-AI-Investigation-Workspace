package uz.caseintel.repository;

import java.util.Optional;
import uz.caseintel.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByCaseEntityId(Long caseId);
}
