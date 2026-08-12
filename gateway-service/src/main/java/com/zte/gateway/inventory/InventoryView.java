package com.zte.gateway.inventory;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link InventoryEntry} left-joined with its {@link HealthMetric} row
 * (ADR-016) — the shape the Admin Console's Inventory table actually needs;
 * health fields are {@code null} until the first poll/routed call happens.
 * Not an R2DBC-mapped entity — {@link InventoryService#list()} builds it in
 * memory from {@link InventoryRepository#findAll()} and {@link
 * HealthMetricRepository#findByServiceIdIn}, keyed by {@code service_id}.
 *
 * <p>{@code hasSchema} (ADR-016 amendment) — whether {@code
 * discovered_schema} is non-{@code NULL} for this entry, joined the same
 * way from {@link InventoryRepository#findIdsWithDiscoveredSchema()}.
 * Deliberately <em>not</em> {@code status == ACTIVE}: that doesn't
 * reliably imply a schema was captured (see {@code AutoDiscoveryWorker}'s
 * Javadoc) — this is the correct, still-lightweight signal the Admin
 * Console uses to enable/disable its "View Schema" button, without ever
 * shipping the payload itself in the list response.
 */
public record InventoryView(
        UUID id,
        String name,
        TargetType targetType,
        String baseUrl,
        String docsUrl,
        String managementUrl,
        InventoryStatus status,
        Instant createdAt,
        Integer lastPingMs,
        String actuatorStatus,
        Instant lastSuccessfulCall,
        boolean hasSchema
) {
}
