package com.zte.gateway.inventory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Current health snapshot for one {@link InventoryEntry} (ADR-016) — one row
 * per service (not a history log), overwritten in place by {@link
 * HealthPollingService} (active polling) and {@link
 * com.zte.gateway.filter.RequestAuditFilter} (passive, on a real 2xx
 * routed response).
 */
@Table("health_metrics")
public record HealthMetric(
        @Id                            UUID    id,
        @Column("service_id")         UUID    serviceId,
        @Column("last_ping_ms")       Integer lastPingMs,
        @Column("actuator_status")    String  actuatorStatus,
        @Column("last_successful_call") Instant lastSuccessfulCall,
        @Column("updated_at")         Instant updatedAt
) {
}
