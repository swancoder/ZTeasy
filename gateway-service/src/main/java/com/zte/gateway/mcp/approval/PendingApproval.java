package com.zte.gateway.mcp.approval;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A tool call held for human approval (Stage 1, ADR-019) — the demo's 🟡
 * outcome: neither forwarded to the backend nor rejected, parked here until
 * {@code POST /api/v1/admin/approvals/{id}/approve} or {@code /reject}.
 *
 * <p>{@code rpcIdJson}/{@code argumentsJson} are the original {@code
 * tools/call} request's {@code id}/{@code params.arguments}, compact-JSON
 * serialized so the exact call can be reconstructed and forwarded unchanged
 * on approval — JSON-RPC {@code id} may be a string or a number, so it isn't
 * stored as a plain string. {@code id} left {@code null} on construction for
 * a not-yet-persisted row, same DB-generated-PK convention {@code
 * RequestLog}/{@code InventoryEntry} already established.
 *
 * <p>May be reviewed well after the originating MCP SSE session has closed
 * (the demo's own "held items reviewed daily" framing) — deliberately
 * DB-backed rather than in-memory, unlike {@link
 * com.zte.gateway.mcp.McpSessionManager}'s per-session sinks. If the session
 * has closed by decision time, the decision still executes and is audited,
 * but its result can't be pushed back into that specific (now-gone) SSE
 * connection — a named POC limitation (see ADR-019).
 */
@Table("pending_approvals")
public record PendingApproval(
        @Id                            UUID   id,
        @Column("session_id")          String sessionId,
        @Column("agent_id")            String agentId,
        @Column("tool_name")           String toolName,
        @Column("rpc_id_json")         String rpcIdJson,
        @Column("arguments_json")      String argumentsJson,
        @Column("route_to")            String routeTo,
                                       String reason,
                                       String status,
        @Column("requested_at")        Instant requestedAt,
        @Column("expires_at")          Instant expiresAt,
        @Column("decided_at")          Instant decidedAt,
        @Column("decided_by")          String decidedBy,
        @Column("trace_id")            String traceId,
        @Column("client_ip")           String clientIp,
        @Column("user_agent")          String userAgent,
        @Column("display_identity")    String displayIdentity
) {

    public static PendingApproval requested(String sessionId, String agentId, String toolName, String rpcIdJson,
                                             String argumentsJson, String routeTo, String reason, String traceId,
                                             String clientIp, String userAgent, String displayIdentity,
                                             Duration ttl) {
        Instant now = Instant.now();
        return new PendingApproval(null, sessionId, agentId, toolName, rpcIdJson, argumentsJson, routeTo, reason,
                PendingApprovalStatus.PENDING.name(), now, now.plus(ttl), null, null, traceId, clientIp, userAgent,
                displayIdentity);
    }

    /** True once {@code expiresAt} is in the past — the sweeper's predicate, and the API's guard. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Same row with a terminal decision recorded — {@code save()} then issues an UPDATE, {@code id} is non-null. */
    public PendingApproval decided(PendingApprovalStatus newStatus, String decidedBy) {
        return new PendingApproval(id, sessionId, agentId, toolName, rpcIdJson, argumentsJson, routeTo, reason,
                newStatus.name(), requestedAt, expiresAt, Instant.now(), decidedBy, traceId, clientIp, userAgent,
                displayIdentity);
    }
}
