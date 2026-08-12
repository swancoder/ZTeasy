package com.zte.gateway.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business layer for the APIM inventory registry (ADR-016): onboard, list,
 * update, and remove REST services / MCP agents this gateway fronts.
 *
 * <p>{@link #create} persists a {@code PENDING} row and returns as soon as
 * that write completes — {@link AutoDiscoveryWorker}'s schema-fetch probe is
 * fired independently (an isolated {@code .subscribe()}, not part of the
 * returned {@code Mono} chain) so onboarding an unreachable or slow service
 * never delays the HTTP response the Admin Console is waiting on.
 *
 * <p>{@code create}/{@code update}/{@code delete} each publish a {@link
 * RefreshRoutesEvent} (ADR-017) so {@link InventoryRouteDefinitionLocator}'s
 * dynamic routes reflect the change immediately, without waiting for {@link
 * InventoryRouteRefreshScheduler}'s periodic catch-all — the operator-facing
 * "onboard/edit/remove a service" actions are exactly the ones worth not
 * making wait.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY");

    private final InventoryRepository repository;
    private final HealthMetricRepository healthMetricRepository;
    private final AutoDiscoveryWorker autoDiscoveryWorker;
    private final ApplicationEventPublisher eventPublisher;

    public InventoryService(InventoryRepository repository, HealthMetricRepository healthMetricRepository,
                             AutoDiscoveryWorker autoDiscoveryWorker, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.healthMetricRepository = healthMetricRepository;
        this.autoDiscoveryWorker = autoDiscoveryWorker;
        this.eventPublisher = eventPublisher;
    }

    /**
     * All registered services, left-joined with their current health
     * snapshot and {@code hasSchema} flag (§ADR-016 — both joined in
     * memory, not via a native query, same as the health join already
     * was).
     */
    public Mono<List<InventoryView>> list() {
        return repository.findAll().collectList()
                .flatMap(entries -> {
                    List<UUID> ids = entries.stream().map(InventoryEntry::id).toList();
                    Mono<Map<UUID, HealthMetric>> health = healthMetricRepository.findByServiceIdIn(ids)
                            .collectMap(HealthMetric::serviceId, Function.identity());
                    Mono<Set<UUID>> idsWithSchema = repository.findIdsWithDiscoveredSchema().collect(Collectors.toSet());

                    return Mono.zip(health, idsWithSchema)
                            .map(tuple -> entries.stream()
                                    .map(entry -> toView(entry, tuple.getT1().get(entry.id()), tuple.getT2().contains(entry.id())))
                                    .toList());
                });
    }

    /**
     * Onboards a new service — fails with {@link DuplicateServiceNameException}
     * if {@code name} is already registered ({@code inventory_services.name}
     * is also DB-{@code UNIQUE}, but checking first gives a clean 409 instead
     * of a raw constraint-violation error).
     */
    public Mono<InventoryEntry> create(String name, TargetType targetType, String baseUrl, String docsUrl, String managementUrl) {
        return repository.existsByName(name)
                .flatMap(exists -> exists
                        ? Mono.<InventoryEntry>error(new DuplicateServiceNameException(name))
                        : repository.save(InventoryEntry.pending(name, targetType, baseUrl, docsUrl, managementUrl)))
                .doOnNext(this::triggerDiscoveryAsync)
                .doOnNext(entry -> refreshRoutes());
    }

    /**
     * Updates the registration fields — fails with {@link
     * DuplicateServiceNameException} if {@code name} collides with a
     * <em>different</em> row ({@link InventoryRepository#existsByNameAndIdNot}
     * excludes {@code id} itself, so a no-rename update never false-positives),
     * or {@link ServiceNotFoundException} if {@code id} doesn't exist (found
     * missing while auditing this class's error handling: {@code
     * updateFields} on an unknown {@code id} silently no-ops, so without this
     * check the endpoint previously returned a bare {@code 200} with an
     * empty body instead of a {@code 404}). Always resets {@code status} to
     * {@code PENDING} and re-triggers discovery — a simplification (the
     * task didn't specify conditional re-discovery only-if-the-URL-changed)
     * chosen because an unconditional reset can never leave a stale
     * {@code ACTIVE} status pointing at a since-changed {@code base_url}.
     */
    public Mono<InventoryEntry> update(UUID id, String name, TargetType targetType, String baseUrl, String docsUrl,
                                        String managementUrl) {
        return repository.existsByNameAndIdNot(name, id)
                .flatMap(exists -> exists
                        ? Mono.<InventoryEntry>error(new DuplicateServiceNameException(name))
                        : repository.updateFields(id, name, targetType.name(), baseUrl, docsUrl, managementUrl,
                                        InventoryStatus.PENDING.name())
                                .then(repository.findById(id))
                                .switchIfEmpty(Mono.error(new ServiceNotFoundException(id))))
                .doOnNext(this::triggerDiscoveryAsync)
                .doOnNext(entry -> refreshRoutes());
    }

    /** Cascades to {@code health_metrics} via {@code ON DELETE CASCADE} — no separate cleanup needed. */
    public Mono<Void> delete(UUID id) {
        return repository.deleteById(id).doOnSuccess(v -> refreshRoutes());
    }

    private void refreshRoutes() {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }

    /**
     * The raw payload {@link AutoDiscoveryWorker} captured on its last
     * successful probe (ADR-016 amendment) — empty if {@code id} doesn't
     * exist, or if nothing has been captured yet (never discovered, or
     * every discovery attempt so far failed/returned nothing cast-able).
     */
    public Mono<String> getDiscoveredSchema(UUID id) {
        return repository.findDiscoveredSchemaById(id);
    }

    /**
     * Synchronous, UI-triggered discovery (ADR-016 amendment, behind
     * {@code POST .../inventory/{id}/schema/fetch}) — unlike the
     * fire-and-forget {@link #triggerDiscoveryAsync}, the caller gets a
     * real success/failure signal: completes normally on success, errors
     * with {@link ServiceNotFoundException} ({@code id} doesn't exist) or
     * {@link SchemaFetchException} (target unreachable, timed out, or
     * returned no valid JSON — see {@link AutoDiscoveryWorker#fetchSchemaNow}).
     */
    public Mono<Void> fetchSchemaNow(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ServiceNotFoundException(id)))
                .flatMap(autoDiscoveryWorker::fetchSchemaNow);
    }

    private void triggerDiscoveryAsync(InventoryEntry entry) {
        autoDiscoveryWorker.discoverAndUpdateStatus(entry)
                .subscribe(
                        v -> {},
                        ex -> log.error("[ZTE-INVENTORY] discovery failed unexpectedly for id={} name={}",
                                entry.id(), entry.name(), ex));
    }

    private InventoryView toView(InventoryEntry entry, HealthMetric health, boolean hasSchema) {
        return new InventoryView(
                entry.id(), entry.name(), entry.targetType(), entry.baseUrl(), entry.docsUrl(), entry.managementUrl(),
                entry.status(), entry.createdAt(),
                health != null ? health.lastPingMs() : null,
                health != null ? health.actuatorStatus() : null,
                health != null ? health.lastSuccessfulCall() : null,
                hasSchema);
    }
}
