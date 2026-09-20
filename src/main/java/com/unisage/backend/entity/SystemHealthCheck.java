package com.unisage.backend.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable, insert-only time-series log of a past health-check run (UNISAGE-62). Deliberately
 * NOT extending {@link BaseEntity}, same reasoning as {@link AuditLog}: a row is never updated
 * after it is written (no updatedAt/updatedBy churn) and must not itself be audited.
 *
 * <p>{@code componentsJson} snapshots the full {@code components} map at the time of the run
 * (each component's status + details, e.g. response time) so the history/timeline view can show
 * what was actually wrong, not just an overall color — see
 * {@code SystemHealthCheckServiceImpl#toResponse}.
 */
@Entity
@Table(name = "system_health_checks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemHealthCheck {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "checked_at", updatable = false, nullable = false)
    private LocalDateTime checkedAt;

    // Mirrors Actuator's Status vocabulary as plain text (UP / DOWN / DEGRADED / OUT_OF_SERVICE)
    // rather than a CHECK-constrained enum, so a custom/future status value never fails a write.
    @Column(name = "overall_status", updatable = false, nullable = false, length = 32)
    private String overallStatus;

    @Column(name = "components_json", updatable = false, nullable = false, columnDefinition = "text")
    private String componentsJson;
}
