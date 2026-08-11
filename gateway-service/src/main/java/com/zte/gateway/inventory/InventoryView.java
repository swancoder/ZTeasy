package com.zte.gateway.inventory;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link InventoryEntry} left-joined with its {@link HealthMetric} row
 * (ADR-016) — the shape the Admin Console's Inventory table actually needs;
 * health fields are {@code null} until the first poll/routed call happens.
 * Not an R2DBC-mapped entity — projected by {@link InventoryRepository#findAllWithHealth()}'s
 * native join query.
 */
public record InventoryView(
        UUID id,
        String name,
        TargetType targetType,
        String baseUrl,
        String managementUrl,
        InventoryStatus status,
        Instant createdAt,
        Integer lastPingMs,
        String actuatorStatus,
        Instant lastSuccessfulCall
) {
}
