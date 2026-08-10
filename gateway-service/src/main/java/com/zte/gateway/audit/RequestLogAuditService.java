package com.zte.gateway.audit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Async, non-blocking {@link RequestLog} writer (ADR-013) — directly mirrors
 * {@code com.zte.gateway.mcp.audit.LoggingMcpAuditService}'s architecture.
 *
 * <p>{@link #record} performs a single non-blocking {@code tryEmitNext} — no
 * I/O on the caller's (request filter) thread. A single subscriber, running
 * on {@link Schedulers#boundedElastic()}, drains the sink and persists each
 * event via R2DBC. On a DB write failure, falls back to an SLF4J warning
 * line instead of propagating the error — the event is degraded to
 * log-only, not lost, and a Postgres hiccup never affects request latency
 * or availability (the sink emit already returned before this runs).
 *
 * <p>Demo-grade: an unbounded buffer and no delivery guarantee across
 * restarts — the same known limitation {@code LoggingMcpAuditService} has,
 * not fixed here either (see ADR-013 Self-Critique).
 */
@Component
public class RequestLogAuditService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-REQUEST-AUDIT");

    private final RequestLogRepository repository;
    private final Sinks.Many<RequestLog> sink = Sinks.many().unicast().onBackpressureBuffer();

    public RequestLogAuditService(RequestLogRepository repository) {
        this.repository = repository;
        sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .concatMap(this::persist)
                .subscribe(v -> {}, ex -> log.error("Request audit stream terminated unexpectedly", ex));
    }

    public void record(RequestLog entry) {
        Sinks.EmitResult result = sink.tryEmitNext(entry);
        if (result.isFailure()) {
            log.warn("Request audit event dropped ({}): {}", result, entry);
        }
    }

    private Mono<Void> persist(RequestLog entry) {
        return repository.save(entry)
                .doOnNext(saved -> log.debug("[ZTE-REQUEST-AUDIT] persisted trace_id={} path={} status={}",
                        entry.traceId(), entry.path(), entry.statusCode()))
                .then()
                .onErrorResume(ex -> {
                    log.warn("[ZTE-REQUEST-AUDIT] DB write failed, SLF4J fallback: trace_id={} path={} status={} error={}",
                            entry.traceId(), entry.path(), entry.statusCode(), ex.toString());
                    return Mono.empty();
                });
    }

    @PreDestroy
    void shutdown() {
        sink.tryEmitComplete();
    }
}
