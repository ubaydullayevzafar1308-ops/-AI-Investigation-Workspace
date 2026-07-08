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
 * Отчёт о подозрительной операции — черновик от LLM (⑨ Report Generator)
 * плюс финальная версия после правок аналитика.
 * Соответствует таблице `reports` (V1__init_schema.sql).
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private Case caseEntity;

    /** Сгенерированный LlmService.generateReport(), см. AI_LAYER_ARCHITECTURE.md §8.3 */
    @Column(name = "draft_text", columnDefinition = "TEXT", nullable = false)
    private String draftText;

    /** Итоговый текст после правок аналитика (PUT /api/cases/{id}/report) */
    @Column(name = "final_text", columnDefinition = "TEXT")
    private String finalText;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;
}
