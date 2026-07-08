package uz.caseintel.repository;

import java.util.List;
import java.util.Optional;
import uz.caseintel.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByInn(String inn);

    List<Company> findByDirectorId(Long directorId);
}
