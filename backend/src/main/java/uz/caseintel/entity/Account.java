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
 * Счёт клиента или компании.
 * Соответствует таблице `accounts` (V1__init_schema.sql).
 *
 * ownerType/ownerId — это НАМЕРЕННО не JPA-связь (@ManyToOne), а простая
 * полиморфная пара "тип + id": счёт может принадлежать либо Client,
 * либо Company, а одна FK-колонка не может ссылаться на две разные
 * таблицы. Разыменование делает DataCollectorService (① в пайплайне)
 * через явный switch по ownerType, а не Hibernate.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    /** Значения ownerType — держим как константы, а не enum, чтобы избежать доп. конвертера. */
    public static final String OWNER_CLIENT = "client";
    public static final String OWNER_COMPANY = "company";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** client | company */
    @Column(name = "owner_type", nullable = false, length = 10)
    private String ownerType;

    /** id из clients или companies, в зависимости от ownerType */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "UZS";

    @Column(name = "opened_at", nullable = false)
    private LocalDate openedAt;

    /** active | closed | frozen */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
