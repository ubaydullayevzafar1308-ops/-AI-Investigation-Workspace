package uz.caseintel.repository;

import java.time.OffsetDateTime;
import java.util.List;
import uz.caseintel.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByFromAccountIdOrToAccountId(Long fromAccountId, Long toAccountId);

    /**
     * Вся история операций счёта (входящие + исходящие) за период —
     * основа для Data Collector (①) при сборке DossierDto и для
     * большинства правил Rule Engine (окна по времени: R01, R02, R05, R08).
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE (t.fromAccount.id = :accountId OR t.toAccount.id = :accountId)
          AND t.txTimestamp BETWEEN :from AND :to
        ORDER BY t.txTimestamp ASC
        """)
    List<Transaction> findByAccountIdAndPeriod(
            @Param("accountId") Long accountId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
