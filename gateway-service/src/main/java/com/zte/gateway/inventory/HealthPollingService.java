package com.zte.gateway.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Periodically pings every registered service's health endpoint and records
 * {@code last_ping_ms}/{@code actuator_status} (ADR-016). {@link #pollNow()}
 * is the shared, directly-callable core — {@link #poll()} is just its
 * {@code @Scheduled} wrapper — mirroring {@code IdentitySyncService}'s
 * {@code refresh()}/{@code syncNow()} split so this is unit-testable without
 * waiting on the real interval.
 *
 * <p>Polls {@code ACTIVE}, {@code WARNING}, and {@code DOWN} services —
 * <em>not</em> {@code PENDING} ones, whose {@code base_url} hasn't even
 * passed {@link AutoDiscoveryWorker}'s first probe yet, so a ping would be
 * premature noise. A successful ping flips {@code DOWN} back to {@code
 * ACTIVE}; a failed ping flips {@code ACTIVE} to {@code DOWN} — {@code
 * WARNING} is never touched by this job (see {@link InventoryStatus}'s
 * Javadoc for why), even though its {@code health_metrics} row is still
 * updated so an operator can see raw connectivity alongside the stuck
 * discovery failure.
 *
 * <p><strong>mTLS:</strong> {@code webClientBuilder} already carries the
 * gateway's mTLS client certificate when {@code zte.mtls.enabled=true} — see
 * {@link AutoDiscoveryWorker}'s Javadoc for the full explanation and how
 * it was verified; the same reasoning applies identically here.
 */
@Service
public class HealthPollingService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY-HEALTH");

    private final InventoryRepository repository;
    private final HealthMetricRepository healthMetricRepository;
    private final WebClient.Builder webClientBuilder;
    private final Duration timeout;

    public HealthPollingService(InventoryRepository repository, HealthMetricRepository healthMetricRepository,
                                 WebClient.Builder webClientBuilder,
                                 @Value("${zte.inventory.health-poll-timeout-ms:3000}") long timeoutMs) {
        this.repository = repository;
        this.healthMetricRepository = healthMetricRepository;
        this.webClientBuilder = webClientBuilder;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Scheduled(fixedDelayString = "${zte.inventory.health-poll-interval-ms:60000}")
    public void poll() {
        pollNow().subscribe(
                count -> log.info("[ZTE-INVENTORY-HEALTH] poll complete: {} service(s) checked", count),
                ex -> log.error("[ZTE-INVENTORY-HEALTH] poll failed", ex));
    }

    /**
     * Pings every non-{@code PENDING} service and updates its health row;
     * returns the count checked. {@code .thenReturn(1)} + {@code reduce} —
     * not {@code Flux<Void>.count()} — deliberately: every write here
     * (health upsert, status update) is a {@code Mono<Void>}, which never
     * emits {@code onNext}, only {@code onComplete}; a {@code Flux} built by
     * {@code flatMap}-ing {@code Mono<Void>}s therefore never emits either,
     * so {@code .count()} on it would silently always return 0. The same
     * {@code Mono<Void>}-has-no-value pitfall this codebase has hit
     * repeatedly (ADR-012/013, and {@code IdentitySyncService.syncRelations}
     * this session) — avoided here from the start rather than found live.
     */
    public Mono<Integer> pollNow() {
        return repository.findAll()
                .filter(entry -> entry.status() != InventoryStatus.PENDING)
                .flatMap(entry -> pingOne(entry).thenReturn(1))
                .reduce(0, Integer::sum)
                .defaultIfEmpty(0);
    }

    private Mono<Void> pingOne(InventoryEntry entry) {
        Instant start = Instant.now();
        return webClientBuilder.baseUrl(entry.baseUrl()).build()
                .get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(timeout)
                .map(body -> {
                    Object status = body.get("status");
                    return status instanceof String s ? s : "UNKNOWN";
                })
                .onErrorReturn("DOWN")
                .flatMap(actuatorStatus -> recordResult(entry, start, actuatorStatus));
    }

    private Mono<Void> recordResult(InventoryEntry entry, Instant start, String actuatorStatus) {
        int elapsedMs = (int) Duration.between(start, Instant.now()).toMillis();
        boolean reachable = !"DOWN".equals(actuatorStatus);

        Mono<Void> writeHealth = healthMetricRepository.upsertPingResult(entry.id(), elapsedMs, actuatorStatus);
        Mono<Void> updateStatus = statusTransition(entry.status(), reachable)
                .map(newStatus -> repository.updateStatus(entry.id(), newStatus.name()))
                .orElse(Mono.empty());

        return writeHealth.then(updateStatus);
    }

    /**
     * {@code ACTIVE}<->{@code DOWN} only — {@code WARNING} is never returned
     * here (see class Javadoc). Package-visible + {@code static} (no instance
     * state needed) specifically so this pure decision logic has a direct
     * unit test ({@code HealthPollingServiceTest}) — the WebClient-calling
     * parts of this class don't (see that test's own Javadoc for why, same
     * precedent as {@code KeycloakIdpAdapter#isSystemClient}).
     */
    static Optional<InventoryStatus> statusTransition(InventoryStatus current, boolean reachable) {
        if (current == InventoryStatus.ACTIVE && !reachable) return Optional.of(InventoryStatus.DOWN);
        if (current == InventoryStatus.DOWN && reachable) return Optional.of(InventoryStatus.ACTIVE);
        return Optional.empty();
    }
}
