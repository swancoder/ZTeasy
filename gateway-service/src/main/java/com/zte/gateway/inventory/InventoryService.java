package com.zte.gateway.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Business layer for the APIM inventory registry (ADR-016): onboard, list,
 * update, and remove REST services / MCP agents this gateway fronts.
 *
 * <p>{@link #create} persists a {@code PENDING} row and returns as soon as
 * that write completes — {@link AutoDiscoveryWorker}'s schema-fetch probe is
 * fired independently (an isolated {@code .subscribe()}, not part of the
 * returned {@code Mono} chain) so onboarding an unreachable or slow service
 * never delays the HTTP response the Admin Console is waiting on.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY");

    private final InventoryRepository repository;
    private final HealthMetricRepository healthMetricRepository;
    private final AutoDiscoveryWorker autoDiscoveryWorker;

    public InventoryService(InventoryRepository repository, HealthMetricRepository healthMetricRepository,
                             AutoDiscoveryWorker autoDiscoveryWorker) {
        this.repository = repository;
        this.healthMetricRepository = healthMetricRepository;
        this.autoDiscoveryWorker = autoDiscoveryWorker;
    }

    /** All registered services, left-joined with their current health snapshot (§ADR-016 — joined in memory, not via a native query). */
    public Mono<List<InventoryView>> list() {
        return repository.findAll().collectList()
                .flatMap(entries -> healthMetricRepository.findByServiceIdIn(entries.stream().map(InventoryEntry::id).toList())
                        .collectMap(HealthMetric::serviceId, Function.identity())
                        .map(healthByServiceId -> entries.stream()
                                .map(entry -> toView(entry, healthByServiceId.get(entry.id())))
                                .toList()));
    }

    /**
     * Onboards a new service — fails with {@link DuplicateServiceNameException}
     * if {@code name} is already registered ({@code inventory_services.name}
     * is also DB-{@code UNIQUE}, but checking first gives a clean 409 instead
     * of a raw constraint-violation error).
     */
    public Mono<InventoryEntry> create(String name, TargetType targetType, String baseUrl, String managementUrl) {
        return repository.existsByName(name)
                .flatMap(exists -> exists
                        ? Mono.<InventoryEntry>error(new DuplicateServiceNameException(name))
                        : repository.save(InventoryEntry.pending(name, targetType, baseUrl, managementUrl)))
                .doOnNext(this::triggerDiscoveryAsync);
    }

    /**
     * Updates the registration fields. Always resets {@code status} to
     * {@code PENDING} and re-triggers discovery — a simplification (the
     * task didn't specify conditional re-discovery only-if-the-URL-changed)
     * chosen because an unconditional reset can never leave a stale
     * {@code ACTIVE} status pointing at a since-changed {@code base_url}.
     */
    public Mono<InventoryEntry> update(UUID id, String name, TargetType targetType, String baseUrl, String managementUrl) {
        return repository.updateFields(id, name, targetType.name(), baseUrl, managementUrl, InventoryStatus.PENDING.name())
                .then(repository.findById(id))
                .doOnNext(this::triggerDiscoveryAsync);
    }

    /** Cascades to {@code health_metrics} via {@code ON DELETE CASCADE} — no separate cleanup needed. */
    public Mono<Void> delete(UUID id) {
        return repository.deleteById(id);
    }

    private void triggerDiscoveryAsync(InventoryEntry entry) {
        autoDiscoveryWorker.discoverAndUpdateStatus(entry)
                .subscribe(
                        v -> {},
                        ex -> log.error("[ZTE-INVENTORY] discovery failed unexpectedly for id={} name={}",
                                entry.id(), entry.name(), ex));
    }

    private InventoryView toView(InventoryEntry entry, HealthMetric health) {
        return new InventoryView(
                entry.id(), entry.name(), entry.targetType(), entry.baseUrl(), entry.managementUrl(),
                entry.status(), entry.createdAt(),
                health != null ? health.lastPingMs() : null,
                health != null ? health.actuatorStatus() : null,
                health != null ? health.lastSuccessfulCall() : null);
    }
}
