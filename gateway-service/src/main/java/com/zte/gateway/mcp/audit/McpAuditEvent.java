package com.zte.gateway.mcp.audit;

import java.time.Instant;

/**
 * A single MCP tool-invocation audit record.
 *
 * <p>Field set matches what a Time-Series DB (e.g. InfluxDB) would index on:
 * {@code processId}/{@code agentId}/{@code toolName} as tags, {@code status} as
 * a field, {@code timestamp} as the point time.
 *
 * <p>{@code sessionId} — the MCP {@code GET /sse} session id, correlating every
 * tool call within one session. No longer this event's {@code
 * request_logs.trace_id} (that's now {@code traceId}, the caller's actual
 * {@code X-Request-Id} — see below); {@code sessionId} is folded into {@code
 * request_logs.message} instead by {@code LoggingMcpAuditService}, so it isn't
 * lost. {@code reason} — {@code PolicyDecision#reason()} for a {@code DENIED}
 * event, {@code null} for {@code ALLOWED}.
 *
 * <p>{@code traceId}/{@code clientIp}/{@code userAgent}/{@code originalUserObo}
 * (unifies the previously-separate REST transport row and MCP semantic row
 * into one — see ADR-017's Self-Criticism table) — the same HTTP context
 * {@code RequestAuditFilter} captures for REST traffic, extracted by {@code
 * McpProxyHandler} from the {@code POST /message} request that triggered this
 * event (not the original {@code GET /sse} handshake, which may be a different
 * connection).
 */
public record McpAuditEvent(String processId, String agentId, String toolName, String status, Instant timestamp,
                             String sessionId, String reason, String traceId, String clientIp, String userAgent,
                             String originalUserObo) {}
