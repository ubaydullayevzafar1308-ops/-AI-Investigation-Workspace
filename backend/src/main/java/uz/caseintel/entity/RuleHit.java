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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Одно сработавшее правило Rule Engine (② в пайплайне) для конкретного кейса.
 * Соответствует таблице `rule_hits` (V1__init_schema.sql).
 *
 * Это ИСТОЧНИК ПРАВДЫ для причин Risk Score (в отличие от denormalized
 * снапшота Case.explanationJson) — каждая строка здесь трассируется до
 * конкретного правила (rule_code) и конкретных транзакций (evidence_json).
 */
@Entity
@Table(name = "rule_hits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private Case caseEntity;

    /** R01..R10 — см. ARCHITECTURE.md §6 */
    @Column(name = "rule_code", nullable = false, length = 10)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** Вклад в Risk Score, см. Rule.weight() */
    @Column(name = "weight", nullable = false)
    private int weight;

    /** id транзакций/связей, суммы, даты — то, на чём сработало правило */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb", nullable = false)
    private String evidenceJson;

    /** Готовая фраза-факт, например "Обнаружено 5 операций..." (см. Rule.check()) */
    @Column(name = "explanation", columnDefinition = "TEXT", nullable = false)
    private String explanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
