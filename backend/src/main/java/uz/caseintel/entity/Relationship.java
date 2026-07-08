package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ребро графа связей между двумя субъектами (клиент или компания).
 * Соответствует таблице `relationships` (V1__init_schema.sql).
 *
 * source/target — полиморфные пары (type + id), как и в Account.
 * Используется Graph Engine (③) для построения подграфа вокруг клиента
 * через рекурсивный CTE (см. ARCHITECTURE.md §7) — тот CTE работает
 * напрямую по SQL, эта сущность нужна для CRUD/сидирования данных.
 */
@Entity
@Table(name = "relationships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Relationship {

    /** founder | director | same_address | same_phone | same_device | frequent_counterparty | family */
    public static final String TYPE_FOUNDER = "founder";
    public static final String TYPE_DIRECTOR = "director";
    public static final String TYPE_SAME_ADDRESS = "same_address";
    public static final String TYPE_SAME_PHONE = "same_phone";
    public static final String TYPE_SAME_DEVICE = "same_device";
    public static final String TYPE_FREQUENT_COUNTERPARTY = "frequent_counterparty";
    public static final String TYPE_FAMILY = "family";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** client | company */
    @Column(name = "source_type", nullable = false, length = 10)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** client | company */
    @Column(name = "target_type", nullable = false, length = 10)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "relation_type", nullable = false, length = 30)
    private String relationType;

    /** 0.00–1.00, насколько уверена система в этой связи (эвристические связи типа same_device менее надёжны, чем founder/director из реестра) */
    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal confidence = BigDecimal.ONE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
