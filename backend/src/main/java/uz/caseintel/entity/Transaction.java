package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Финансовая операция между двумя счетами.
 * Соответствует таблице `transactions` (V1__init_schema.sql).
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    /** Допустимые значения txType — держим как константы для использования в правилах (Rule Engine). */
    public static final String TYPE_TRANSFER = "transfer";
    public static final String TYPE_CASH_IN = "cash_in";
    public static final String TYPE_CASH_OUT = "cash_out";
    public static final String TYPE_CARD_PAYMENT = "card_payment";
    public static final String TYPE_INTERNATIONAL = "international";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account")
    private Account toAccount;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "UZS";

    /** transfer | cash_in | cash_out | card_payment | international */
    @Column(name = "tx_type", nullable = false, length = 20)
    private String txType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tx_timestamp", nullable = false)
    private OffsetDateTime txTimestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
