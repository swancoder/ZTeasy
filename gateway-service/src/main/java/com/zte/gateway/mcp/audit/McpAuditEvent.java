package com.zte.gateway.mcp.audit;

import java.time.Instant;

/**
 * A single MCP audit record — either a session opening or a tool call's outcome.
 *
 * <p>Field set matches what a Time-Series DB (e.g. InfluxDB) would index on:
 * {@code processId}/{@code agentId}/{@code toolName} as tags, {@code status} as
 * a field, {@code timestamp} as the point time.
 *
 * <p>{@code status}: {@code "SSE_OPENED"} ({@code GET /sse} — a session was
 * opened; {@code toolName}/{@code reason} are {@code null}, since opening a
 * session isn't itself a policy decision — only which tool calls it's then
 * allowed to make is), {@code "ALLOWED"}/{@code "DENIED"} ({@code POST
 * /message} — a tool call's outcome; {@code reason} is {@code
 * PolicyDecision#reason()} for {@code DENIED}, {@code null} for {@code
 * ALLOWED}).
 *
 * <p>{@code sessionId} — the MCP {@code GET /sse} session id, correlating every
 * event within one session (its own {@code SSE_OPENED} event, and every
 * subsequent tool call). Not this event's {@code request_logs.trace_id}
 * (that's {@code traceId}, the caller's actual {@code X-Request-Id} — see
 * below); {@code sessionId} is folded into {@code request_logs.message}
 * instead by {@code LoggingMcpAuditService}, so it isn't lost.
 *
 * <p>{@code traceId}/{@code clientIp}/{@code userAgent}/{@code originalUserObo}
 * (unifies the previously-separate REST transport row and MCP semantic row
 * into one — see ADR-017's Self-Criticism table) — the same HTTP context
 * {@code RequestAuditFilter} captures for REST traffic, extracted by {@code
 * McpProxyHandler} from whichever request actually triggered this event
 * ({@code GET /sse} for {@code SSE_OPENED}, {@code POST /message} for a tool
 * call) — these are different connections and may carry different
 * proxy/forwarding headers.
 *
 * <p>{@code argumentsJson} — the {@code tools/call} request's {@code
 * params.arguments}, compact-JSON-serialized ({@code null} for {@code
 * SSE_OPENED}, which has no arguments). Folded into {@code
 * request_logs.message} by {@code LoggingMcpAuditService} alongside the
 * session id, surfaced in the Admin Console as a tooltip on the Tool-name
 * cell — "what was actually inside the /message call."
 */
public record McpAuditEvent(String processId, String agentId, String toolName, String status, Instant timestamp,
                             String sessionId, String reason, String traceId, String clientIp, String userAgent,
                             String originalUserObo, String argumentsJson) {}
