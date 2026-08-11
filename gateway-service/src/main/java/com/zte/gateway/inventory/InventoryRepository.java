package com.zte.gateway.inventory;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link InventoryEntry} (ADR-016).
 *
 * <p>No custom join-projection query here — {@link InventoryService} builds
 * the combined {@link InventoryView} list in memory from this repository's
 * plain {@code findAll()} and {@link HealthMetricRepository}'s, keyed by
 * {@code service_id}. At this project's MVP scale (a handful of registered
 * services), that's simpler and more reliably testable than relying on
 * Spring Data R2DBC's unannotated-DTO projection support for a native join
 * query — matches this codebase's established "don't add machinery MVP
 * scale doesn't need" bias (e.g. {@code PolicyMatcher}'s full linear scan).
 */
public interface InventoryRepository extends ReactiveCrudRepository<InventoryEntry, UUID> {

    Mono<Boolean> existsByName(String name);

    /**
     * Sets {@code status} directly, without a read-then-write {@code save()}
     * round trip — used by {@link AutoDiscoveryWorker} and {@link
     * HealthPollingService}, both of which only ever need to change this one
     * column and must not clobber concurrent field changes from elsewhere.
     */
    @Modifying
    @Query("UPDATE inventory_services SET status = :status WHERE id = :id")
    Mono<Void> updateStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * Updates the operator-editable onboarding fields, leaving {@code
     * created_at} untouched — deliberately not {@code save()}: constructing
     * a full replacement {@link InventoryEntry} for an update means either a
     * read-then-write round trip to preserve {@code created_at} (a race) or
     * passing it as {@code null} (which {@code save()}'s plain {@code
     * UPDATE ... SET created_at = NULL} would then violate the column's
     * {@code NOT NULL} constraint on — found live running this feature's own
     * integration test).
     */
    @Modifying
    @Query("""
            UPDATE inventory_services
            SET name = :name, target_type = :targetType, base_url = :baseUrl, status = :status
            WHERE id = :id
            """)
    Mono<Void> updateFields(@Param("id") UUID id, @Param("name") String name, @Param("targetType") String targetType,
                             @Param("baseUrl") String baseUrl, @Param("status") String status);
}
