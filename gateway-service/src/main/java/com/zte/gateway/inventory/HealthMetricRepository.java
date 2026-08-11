package com.zte.gateway.inventory;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link HealthMetric} (ADR-016) — always one
 * row per service ({@code UNIQUE (service_id)}), upserted in place.
 */
public interface HealthMetricRepository extends ReactiveCrudRepository<HealthMetric, UUID> {

    Flux<HealthMetric> findByServiceIdIn(Collection<UUID> serviceIds);

    /**
     * Written by {@link HealthPollingService}'s periodic ping — real Postgres
     * {@code ON CONFLICT} upsert (same rationale as {@code IdpIdentityRepository#upsert}:
     * {@code save()}'s null-id convention would attempt a fresh INSERT every
     * poll cycle and violate {@code UNIQUE (service_id)} on the second one).
     * Leaves {@code last_successful_call} untouched — a failed/slow ping says
     * nothing about whether real traffic is still succeeding.
     */
    @Modifying
    @Query("""
            INSERT INTO health_metrics (service_id, last_ping_ms, actuator_status, updated_at)
            VALUES (:serviceId, :lastPingMs, :actuatorStatus, NOW())
            ON CONFLICT (service_id)
            DO UPDATE SET last_ping_ms = EXCLUDED.last_ping_ms,
                          actuator_status = EXCLUDED.actuator_status,
                          updated_at = NOW()
            """)
    Mono<Void> upsertPingResult(@Param("serviceId") UUID serviceId, @Param("lastPingMs") Integer lastPingMs,
                                 @Param("actuatorStatus") String actuatorStatus);

    /**
     * Written by {@link com.zte.gateway.filter.RequestAuditFilter} (via {@link
     * HealthTelemetryService}) on every 2xx response for a routed request —
     * fire-and-forget, must never block the response. Resolves {@code
     * inventory_services.id} from the request's target service <em>name</em>
     * via a {@code SELECT} subquery in the same statement — one round trip,
     * no read-then-write race (same rationale {@code IdpIdentityRepository#upsert}
     * documents), and a target name that isn't registered in the inventory
     * simply inserts zero rows — a harmless no-op, not an error, since not
     * every routed path is expected to have an inventory entry.
     */
    @Modifying
    @Query("""
            INSERT INTO health_metrics (service_id, last_successful_call, updated_at)
            SELECT id, NOW(), NOW() FROM inventory_services WHERE name = :serviceName
            ON CONFLICT (service_id)
            DO UPDATE SET last_successful_call = NOW(), updated_at = NOW()
            """)
    Mono<Void> upsertSuccessfulCallByServiceName(@Param("serviceName") String serviceName);
}
