package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Модуль платформы (AML / Fraud / Credit / KYC / Compliance) — вкл/выкл.
 * Соответствует таблице `feature_flags` (V3__feature_flags.sql).
 *
 * Используется FeatureFlagService (ARCHITECTURE.md §13) и экраном
 * Modules.jsx на фронте: показывает судьям, что MVP (AML) — это один
 * модуль платформы, а не весь продукт целиком.
 */
@Entity
@Table(name = "feature_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureFlag {

    public static final String MODULE_AML = "AML";
    public static final String MODULE_FRAUD = "FRAUD";
    public static final String MODULE_CREDIT = "CREDIT";
    public static final String MODULE_KYC = "KYC";
    public static final String MODULE_COMPLIANCE = "COMPLIANCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module_code", nullable = false, unique = true, length = 30)
    private String moduleCode;

    @Column(name = "module_name", nullable = false, length = 100)
    private String moduleName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
