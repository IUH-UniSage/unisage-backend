package com.unisage.backend.entity;

import java.util.UUID;

import com.unisage.backend.entity.enums.SystemConfigCategory;
import com.unisage.backend.entity.enums.ValueType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Generic key-value system configuration row (UNISAGE-64). A plain {@link BaseEntity} subclass —
 * every INSERT/UPDATE is captured automatically by {@code AuditHibernateIntegrator}
 * (see the UNISAGE-60 audit-log feature), no manual audit calls needed here.
 *
 * <p>{@code value} is always stored as a string and parsed/validated against {@code valueType} in
 * {@code SystemConfigServiceImpl} before persisting an update. Rows are seeded via Flyway
 * migration only — this ticket's API is read + update, not create/delete.
 */
@Entity
@Table(name = "system_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class SystemConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "config_key", nullable = false, unique = true)
    private String configKey;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private ValueType valueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private SystemConfigCategory category;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "description")
    private String description;

    @Builder.Default
    @Column(name = "is_editable", nullable = false)
    private Boolean isEditable = true;
}
