package com.zte.gateway.inventory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single registered REST service or MCP agent (ADR-016) — the APIM
 * inventory's core record. {@code id} left {@code null} on construction for
 * a not-yet-persisted entry, same DB-generated-PK convention {@code
 * RequestLog}/{@code IdpIdentity} already established.
 *
 * <p>{@code managementUrl} (ADR-016 amendment) is optional — {@code null}
 * for the common case where a target's health endpoint lives at {@code
 * baseUrl}. Set it when a service's {@code /actuator/health} lives
 * elsewhere, e.g. a separate management port alongside an mTLS API port
 * (see {@link HealthPollingService}).
 *
 * <p>{@code docsUrl} (ADR-016 amendment) is optional and {@code REST}-only
 * — a full absolute URL {@link AutoDiscoveryWorker} probes instead of the
 * {@code {baseUrl}/v3/api-docs} convention. {@code null} keeps that
 * convention; ignored for {@code MCP} targets (no equivalent override
 * exists for the {@code tools/list} convention).
 */
@Table("inventory_services")
public record InventoryEntry(
        @Id                          UUID         id,
                                     String       name,
        @Column("target_type")      TargetType   targetType,
        @Column("base_url")         String       baseUrl,
        @Column("docs_url")         String       docsUrl,
        @Column("management_url")   String       managementUrl,
                                     InventoryStatus status,
        @Column("created_at")       Instant      createdAt
) {

    /** A freshly submitted onboarding request — always starts {@code PENDING}. */
    public static InventoryEntry pending(String name, TargetType targetType, String baseUrl, String docsUrl,
                                          String managementUrl) {
        return new InventoryEntry(null, name, targetType, baseUrl, docsUrl, managementUrl, InventoryStatus.PENDING, null);
    }
}
