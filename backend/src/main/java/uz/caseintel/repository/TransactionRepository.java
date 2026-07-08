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
     * Суммарный оборот по всем счетам заданного владельца (клиент или
     * компания) — агрегат, нужный CompanyView.turnoverAmount для R04
     * (New entity spike). Считается один раз в Data Collector'е, чтобы
     * само правило видело уже готовое число, а не сырые транзакции
     * чужой компании (см. комментарий в DossierDto.CompanyView).
     */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE (t.fromAccount.ownerType = :ownerType AND t.fromAccount.ownerId = :ownerId)
           OR (t.toAccount.ownerType = :ownerType AND t.toAccount.ownerId = :ownerId)
        """)
    java.math.BigDecimal sumTurnoverByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    /**
     * Все транзакции по набору счетов (обычно — все счета одного клиента),
     * без ограничения по времени. Используется Data Collector'ом (①) для
     * сборки полной истории клиента в DossierDto — большинство правил сами
     * решают, какое окно им интересно (см. константы WINDOW_DAYS и т.п.
     * в rules.impl.*), поэтому Data Collector отдаёт им всё, что есть.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.fromAccount.id IN :accountIds OR t.toAccount.id IN :accountIds
        ORDER BY t.txTimestamp ASC
        """)
    List<Transaction> findByAccountIdIn(@Param("accountIds") List<Long> accountIds);

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
