package com.zte.gateway.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single request audit row (ADR-013, extended ADR-017) — written
 * asynchronously by {@link RequestLogAuditService}, read back via
 * {@code GET /api/v1/admin/audit-logs}.
 *
 * <p>{@code agentId}/{@code toolName} were always {@code null} from the REST
 * gateway path ({@link com.zte.gateway.filter.RequestAuditFilter}) until
 * ADR-017 wired {@code com.zte.gateway.mcp.audit.LoggingMcpAuditService} into
 * this same table — they're populated for MCP tool calls, still {@code null}
 * for plain REST traffic (a request has one or the other, never both).
 *
 * <p>{@code initiatorClient}/{@code originalUserObo}/{@code targetService}/
 * {@code httpMethod}/{@code decisionEffect} (ADR-017) — see
 * {@code V12__extend_request_logs.sql}'s column comments for what each
 * means and why {@code decisionEffect} is a coarse, status-code-derived
 * signal rather than per-policy-rule provenance. {@code originalUserObo}'s
 * meaning shifted slightly, undocumented there: it's now the caller's JWT
 * {@code preferred_username} (falling back to {@code sub} if absent) for
 * human-readable display in the Admin Console, not necessarily the exact
 * value embedded in the OBO token the gateway mints for downstream
 * propagation — that's always the raw {@code sub}, read independently by
 * {@code UserContextPropagationFilter} straight from the security context,
 * unaffected by this column's display choice.
 *
 * <p>{@code id} is left {@code null} on construction for new rows —
 * {@code request_logs.id} is {@code DEFAULT gen_random_uuid()}, and Spring
 * Data's standard "null id → new entity" heuristic triggers an INSERT
 * without a custom {@code Persistable} implementation, mirroring the old
 * {@code AccessPolicy} record's DB-generated {@code BIGSERIAL} convention.
 */
@Table("request_logs")
public record RequestLog(
        @Id                            UUID    id,
                                       Instant timestamp,
        @Column("trace_id")            String  traceId,
        @Column("client_ip")           String  clientIp,
        @Column("user_agent")          String  userAgent,
        @Column("process_id")          String  processId,
        @Column("agent_id")            String  agentId,
        @Column("tool_name")           String  toolName,
                                       String  path,
        @Column("status_code")         Integer statusCode,
                                       String  message,
        @Column("initiator_client")    String  initiatorClient,
        @Column("original_user_obo")   String  originalUserObo,
        @Column("target_service")      String  targetService,
        @Column("http_method")         String  httpMethod,
        @Column("decision_effect")     String  decisionEffect
) {

    /**
     * REST gateway traffic ({@link com.zte.gateway.filter.RequestAuditFilter})
     * — {@code agentId}/{@code toolName} are {@code null} (MCP-only columns).
     */
    public static RequestLog forRest(String traceId, String clientIp, String userAgent, String processId,
                                      String path, Integer statusCode, String message, String initiatorClient,
                                      String originalUserObo, String targetService, String httpMethod,
                                      String decisionEffect) {
        return new RequestLog(null, Instant.now(), traceId, clientIp, userAgent, processId, null, null, path,
                statusCode, message, initiatorClient, originalUserObo, targetService, httpMethod, decisionEffect);
    }

    /**
     * MCP tool-call traffic ({@code com.zte.gateway.mcp.audit.LoggingMcpAuditService},
     * ADR-017, unified with the REST transport row as a follow-up — see that
     * ADR's Self-Criticism table) — {@code initiatorClient} is set to {@code
     * agentId} too, so a "who initiated this row" query never has to branch on
     * target type. Deliberately not a separate {@code initiatorClient} parameter:
     * there is no scenario where an MCP row's initiator legitimately differs
     * from the calling agent, and accepting one anyway would let the two
     * silently drift apart for no benefit.
     *
     * <p>{@code traceId} is the caller's {@code X-Request-Id} (minted if absent),
     * the same convention REST traffic uses — the MCP session id is a separate,
     * longer-lived correlator (one session spans many tool calls) and doesn't
     * fit the "one row, one trace" shape {@code trace_id} is queried by; it's
     * preserved in {@code message} instead (see {@code LoggingMcpAuditService}).
     *
     * <p>{@code httpMethod} is a real parameter, not hardcoded {@code "POST"}:
     * an {@code SSE_OPENED} row (from {@code GET /sse}) needs {@code "GET"}
     * here to stay accurate, alongside every tool-call row (from
     * {@code POST /message}), which still passes {@code "POST"}.
     */
    public static RequestLog forMcp(String traceId, String clientIp, String userAgent, String processId,
                                     String agentId, String toolName, String path, String httpMethod,
                                     Integer statusCode, String message, String originalUserObo,
                                     String targetService, String decisionEffect) {
        return new RequestLog(null, Instant.now(), traceId, clientIp, userAgent, processId, agentId, toolName, path,
                statusCode, message, agentId, originalUserObo, targetService, httpMethod, decisionEffect);
    }
}
