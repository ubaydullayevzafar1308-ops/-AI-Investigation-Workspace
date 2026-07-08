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
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Сигнал о подозрительной операции от системы мониторинга.
 * Соответствует таблице `alerts` (V1__init_schema.sql).
 *
 * Alert — это ВХОД в пайплайн Case Builder. POST /api/alerts/{id}/investigate
 * запускает CaseBuilderService.buildCase(alertId), который превращает
 * этот alert в полноценный Case.
 */
@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    /** low | medium | high */
    public static final String SEVERITY_LOW = "low";
    public static final String SEVERITY_MEDIUM = "medium";
    public static final String SEVERITY_HIGH = "high";

    /** new | investigating | closed */
    public static final String STATUS_NEW = "new";
    public static final String STATUS_INVESTIGATING = "investigating";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "trigger_reason", nullable = false, length = 100)
    private String triggerReason;

    /** low | medium | high */
    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    /** new | investigating | closed */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
