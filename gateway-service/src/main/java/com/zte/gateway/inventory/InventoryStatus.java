package com.zte.gateway.inventory;

/**
 * An {@link InventoryEntry}'s discovery/health lifecycle state (ADR-016).
 *
 * <p>{@code PENDING} → {@code ACTIVE}/{@code WARNING} is set once by {@link
 * AutoDiscoveryWorker} right after registration (schema fetch succeeded or
 * failed/timed out — {@code WARNING} means "reachable enough to route, but
 * its schema/tool list couldn't be confirmed," a degraded state requiring
 * manual routing configuration, not a hard failure).
 *
 * <p>{@code ACTIVE} ↔ {@code DOWN} is toggled repeatedly by {@link
 * HealthPollingService}'s periodic ping — the only state this job ever
 * touches. It deliberately never touches {@code WARNING}: a failed schema
 * discovery is a different kind of problem than a failed health ping, and
 * silently "fixing" a {@code WARNING} service just because its health
 * endpoint happens to respond would hide the original discovery failure.
 */
public enum InventoryStatus {
    PENDING, ACTIVE, WARNING, DOWN
}
