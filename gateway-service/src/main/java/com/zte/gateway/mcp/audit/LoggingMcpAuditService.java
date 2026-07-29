package com.zte.gateway.mcp.audit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * {@link McpAuditService} backed by an in-process async sink.
 *
 * <p>{@link #record} performs a single non-blocking {@code tryEmitNext} — no I/O
 * on the caller's (proxy request) thread. A single subscriber, running on
 * {@link Schedulers#boundedElastic()}, drains the sink and persists each event via
 * {@link #persist}. Today {@link #persist} just logs; swapping it for an InfluxDB
 * line-protocol write is the only change needed to wire up a real TSDB — the
 * non-blocking contract on the caller's side does not change.
 *
 * <p>Demo-grade: an unbounded buffer and no delivery guarantee across restarts.
 * A production version would back the sink with a persistent queue.
 */
@Component
public class LoggingMcpAuditService implements McpAuditService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-MCP-AUDIT");

    private final Sinks.Many<McpAuditEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    public LoggingMcpAuditService() {
        sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(this::persist, ex -> log.error("MCP audit stream terminated unexpectedly", ex));
    }

    @Override
    public void record(McpAuditEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("MCP audit event dropped ({}): {}", result, event);
        }
    }

    private void persist(McpAuditEvent event) {
        // TODO: replace with an InfluxDB line-protocol write. Runs off the request
        // thread (boundedElastic), so a slow/blocking write here is safe to add.
        log.info("[ZTE-MCP-AUDIT] process={} agent={} tool={} status={} ts={}",
                event.processId(), event.agentId(), event.toolName(), event.status(), event.timestamp());
    }

    @PreDestroy
    void shutdown() {
        sink.tryEmitComplete();
    }
}
