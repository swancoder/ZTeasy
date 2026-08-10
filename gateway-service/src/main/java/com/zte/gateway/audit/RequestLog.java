package com.zte.gateway.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single request audit row (ADR-013) — written asynchronously by
 * {@link RequestLogAuditService}, read back via {@code GET /api/v1/admin/audit-logs}.
 *
 * <p>{@code agentId}/{@code toolName} are always {@code null} from the REST
 * gateway path ({@link com.zte.gateway.filter.RequestAuditFilter}) — reserved
 * for a future MCP-audit unification (see ADR-013 Future Migration Path).
 *
 * <p>{@code id} is left {@code null} on construction for new rows —
 * {@code request_logs.id} is {@code DEFAULT gen_random_uuid()}, and Spring
 * Data's standard "null id → new entity" heuristic triggers an INSERT
 * without a custom {@code Persistable} implementation, mirroring the old
 * {@code AccessPolicy} record's DB-generated {@code BIGSERIAL} convention.
 */
@Table("request_logs")
public record RequestLog(
        @Id                       UUID    id,
                                  Instant timestamp,
        @Column("trace_id")       String  traceId,
        @Column("client_ip")      String  clientIp,
        @Column("user_agent")     String  userAgent,
        @Column("process_id")     String  processId,
        @Column("agent_id")       String  agentId,
        @Column("tool_name")      String  toolName,
                                  String  path,
        @Column("status_code")    Integer statusCode,
                                  String  message
) {

    /** New row — {@code id}/{@code timestamp} are assigned here or by the DB default. */
    public static RequestLog of(String traceId, String clientIp, String userAgent, String processId,
                                 String path, Integer statusCode, String message) {
        return new RequestLog(null, Instant.now(), traceId, clientIp, userAgent, processId,
                null, null, path, statusCode, message);
    }
}
