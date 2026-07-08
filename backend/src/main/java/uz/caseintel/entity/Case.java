package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
 * Готовое расследование (Case) — центральная сущность продукта.
 * Соответствует таблице `cases` (V1__init_schema.sql).
 *
 * dossier_json / evidence_json / explanation_json — это СНАПШОТЫ
 * состояния на момент сборки кейса (записываются один раз в конце
 * пайплайна CaseBuilderService.buildCase). Источник правды при
 * повторном построении того же кейса — это заново прогнанный пайплайн
 * (rule_hits таблица + Evidence/Risk/Explainability engines), а не эти
 * JSONB-поля: они существуют для быстрого чтения на фронте
 * (GET /api/cases/{id} отдаёт их одним запросом без пересборки) и для
 * audit-неизменности (что именно видел аналитик в момент решения).
 * Если понадобится "пересчитать" кейс — это отдельная операция, которая
 * создаёт новую версию, а не перезаписывает эти поля молча.
 *
 * Используем @JdbcTypeCode(SqlTypes.JSON) — нативная поддержка JSONB
 * в Hibernate 6.x, без доп. зависимостей (hypersistence-utils и т.п.).
 * Хранится и читается как обычный Map/String через Jackson.
 */
@Entity
@Table(name = "cases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Case {

    /** open | approved | rejected | escalated */
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_ESCALATED = "escalated";

    /** low | medium | high */
    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", unique = true)
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "risk_score", nullable = false)
    @Builder.Default
    private int riskScore = 0;

    /** low | medium | high */
    @Column(name = "risk_level", nullable = false, length = 10)
    @Builder.Default
    private String riskLevel = RISK_LOW;

    /** open | approved | rejected | escalated */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_OPEN;

    @Column(name = "analyst_decision", columnDefinition = "TEXT")
    private String analystDecision;

    /** Снапшот досье клиента (DossierDto) на момент сборки кейса. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dossier_json", columnDefinition = "jsonb")
    private String dossierJson;

    /** Снапшот пакета доказательств (EvidenceBundle) на момент сборки кейса. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    /** Снапшот объяснения score (ExplanationDto) на момент сборки кейса. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_json", columnDefinition = "jsonb")
    private String explanationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;
}
