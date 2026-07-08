package uz.caseintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Физическое лицо — клиент банка.
 * Соответствует таблице `clients` (V1__init_schema.sql).
 */
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "inn", unique = true, length = 14)
    private String inn;

    @Column(name = "phone", length = 20)
    private String phone;

    /** Идентификатор устройства — основа правила R07 (Shared attributes / Same Device). */
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    /** individual | entrepreneur */
    @Column(name = "client_type", nullable = false, length = 20)
    @Builder.Default
    private String clientType = "individual";

    /** low | medium | high */
    @Column(name = "risk_level", nullable = false, length = 10)
    @Builder.Default
    private String riskLevel = "low";

    @Column(name = "is_blacklisted", nullable = false)
    @Builder.Default
    private boolean blacklisted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
