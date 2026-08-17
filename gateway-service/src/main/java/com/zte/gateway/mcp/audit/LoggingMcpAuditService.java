package com.zte.gateway.mcp.audit;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogAuditService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * {@link McpAuditService} backed by an in-process async sink.
 *
 * <p>{@link #record} performs a single non-blocking {@code tryEmitNext} — no I/O
 * on the caller's (proxy request) thread. A single subscriber, running on
 * {@link Schedulers#boundedElastic()}, drains the sink and persists each event via
 * {@link #persist}. Today {@link #persist} logs (as before) <em>and</em>, since
 * ADR-017, also writes into {@code request_logs} via {@link RequestLogAuditService}
 * — the same table REST traffic uses, unifying the two previously-separate audit
 * paths ADR-009 flagged as future work. Swapping the log line for an InfluxDB
 * line-protocol write is still the only change needed to additionally wire up a
 * real TSDB — the non-blocking contract on the caller's side does not change.
 *
 * <p>{@code targetService} (persisted rows' "which MCP backend" — the Admin
 * Console's Audit Trail "Target" column) is {@code mcp-backend.name}, a
 * display label the operator sets alongside {@code mcp-backend.uri} — this
 * gateway only ever forwards to that single configured backend (see {@link
 * com.zte.gateway.mcp.McpBackendClient}), not looked up from the APIM
 * Registry per-call, so it's this filter's own config value, not a live
 * lookup.
 *
 * <p>Demo-grade: an unbounded buffer and no delivery guarantee across restarts.
 * A production version would back the sink with a persistent queue.
 */
@Component
public class LoggingMcpAuditService implements McpAuditService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-MCP-AUDIT");

    private final RequestLogAuditService requestLogAuditService;
    private final String targetServiceName;
    private final Sinks.Many<McpAuditEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    public LoggingMcpAuditService(RequestLogAuditService requestLogAuditService,
                                   @Value("${mcp-backend.name:hubspot-mcp}") String targetServiceName) {
        this.requestLogAuditService = requestLogAuditService;
        this.targetServiceName = targetServiceName;
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

        // Three event shapes share this one persist() path: a session opening (GET /sse,
        // no policy decision — always "succeeds") and a tool call's allow/deny outcome
        // (POST /message). Only "DENIED" is ever a non-2xx/non-ALLOW row.
        boolean denied = "DENIED".equals(event.status());
        boolean sseOpened = "SSE_OPENED".equals(event.status());
        String path = sseOpened ? "/sse" : "/message";
        String httpMethod = sseOpened ? "GET" : "POST";

        // sessionId no longer travels as trace_id (see RequestLog.forMcp's Javadoc), and
        // argumentsJson has no dedicated column either — both folded into message instead,
        // so they're still queryable/visible without another schema migration. The Admin
        // Console shows this as a tooltip on the Tool-name cell ("what was inside the
        // /message call").
        StringBuilder message = new StringBuilder("Session: ").append(event.sessionId());
        if (event.reason() != null) {
            message.append(". ").append(event.reason());
        }
        if (event.argumentsJson() != null) {
            message.append(". Args: ").append(event.argumentsJson());
        }
        requestLogAuditService.record(RequestLog.forMcp(
                event.traceId(), event.clientIp(), event.userAgent(), event.processId(), event.agentId(),
                event.toolName(), path, httpMethod, denied ? 403 : 200, message.toString(), event.originalUserObo(),
                targetServiceName, denied ? "DENY" : "ALLOW"));
    }

    @PreDestroy
    void shutdown() {
        sink.tryEmitComplete();
    }
}
