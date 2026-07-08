package uz.caseintel.repository;

import java.util.Optional;
import uz.caseintel.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByInn(String inn);

    Optional<Client> findByDeviceId(String deviceId);
}
