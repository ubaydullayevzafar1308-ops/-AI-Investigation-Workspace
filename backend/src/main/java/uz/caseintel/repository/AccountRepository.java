package uz.caseintel.repository;

import java.util.List;
import java.util.Optional;
import uz.caseintel.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    /** Все счета клиента или компании — ownerType это Account.OWNER_CLIENT / OWNER_COMPANY. */
    List<Account> findByOwnerTypeAndOwnerId(String ownerType, Long ownerId);
}
