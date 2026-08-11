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
 */
@Table("inventory_services")
public record InventoryEntry(
        @Id                      UUID         id,
                                 String       name,
        @Column("target_type")  TargetType   targetType,
        @Column("base_url")     String       baseUrl,
                                 InventoryStatus status,
        @Column("created_at")   Instant      createdAt
) {

    /** A freshly submitted onboarding request — always starts {@code PENDING}. */
    public static InventoryEntry pending(String name, TargetType targetType, String baseUrl) {
        return new InventoryEntry(null, name, targetType, baseUrl, InventoryStatus.PENDING, null);
    }
}
