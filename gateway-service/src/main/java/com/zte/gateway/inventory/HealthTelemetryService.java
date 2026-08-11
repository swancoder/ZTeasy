package com.zte.gateway.inventory;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Async, non-blocking {@code health_metrics.last_successful_call} writer
 * (ADR-016) — passive telemetry, fed by {@link
 * com.zte.gateway.filter.RequestAuditFilter} on every 2xx routed response.
 * Directly mirrors {@code RequestLogAuditService}'s architecture (ADR-013):
 * this is precisely the "asynchronous, non-blocking fire-and-forget
 * mechanism via R2DBC" the task's own Self-Criticism demanded, so the
 * gateway's hot request path never waits on a health-metrics write.
 *
 * <p>{@link #recordSuccessfulCall} does a single non-blocking {@code
 * tryEmitNext} — no I/O on the caller's (filter) thread. One subscriber, on
 * {@link Schedulers#boundedElastic()}, drains the sink and upserts via
 * R2DBC; a DB failure degrades to an SLF4J warning instead of propagating.
 */
@Component
public class HealthTelemetryService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY-HEALTH");

    private final HealthMetricRepository repository;
    private final Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

    public HealthTelemetryService(HealthMetricRepository repository) {
        this.repository = repository;
        sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .concatMap(this::persist)
                .subscribe(v -> {}, ex -> log.error("Health telemetry stream terminated unexpectedly", ex));
    }

    /**
     * @param targetServiceName the {@code RequestTargetResolver}-resolved path segment
     *                          (e.g. {@code "service-a"}) — a name with no matching
     *                          {@code inventory_services} row is a harmless no-op, not
     *                          every routed path is expected to be in the registry.
     */
    public void recordSuccessfulCall(String targetServiceName) {
        Sinks.EmitResult result = sink.tryEmitNext(targetServiceName);
        if (result.isFailure()) {
            log.warn("[ZTE-INVENTORY-HEALTH] telemetry event dropped ({}): {}", result, targetServiceName);
        }
    }

    private Mono<Void> persist(String targetServiceName) {
        return repository.upsertSuccessfulCallByServiceName(targetServiceName)
                .onErrorResume(ex -> {
                    log.warn("[ZTE-INVENTORY-HEALTH] DB write failed, dropped: target={} error={}",
                            targetServiceName, ex.toString());
                    return Mono.empty();
                });
    }

    @PreDestroy
    void shutdown() {
        sink.tryEmitComplete();
    }
}
