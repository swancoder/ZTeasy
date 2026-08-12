package com.zte.gateway.mcp.audit;

import java.time.Instant;

/**
 * A single MCP tool-invocation audit record.
 *
 * <p>Field set matches what a Time-Series DB (e.g. InfluxDB) would index on:
 * {@code processId}/{@code agentId}/{@code toolName} as tags, {@code status} as
 * a field, {@code timestamp} as the point time.
 *
 * <p>{@code sessionId} (ADR-017) — the MCP {@code GET /sse} session id, used
 * as this event's {@code request_logs.trace_id} equivalent once persisted:
 * MCP calls don't carry an {@code X-Request-Id} the way REST traffic does,
 * and {@code sessionId} is the closest thing MCP has to a per-flow
 * correlation identifier. {@code reason} (ADR-017) — {@code
 * PolicyDecision#reason()} for a {@code DENIED} event, {@code null} for
 * {@code ALLOWED} — becomes {@code request_logs.message}.
 */
public record McpAuditEvent(String processId, String agentId, String toolName, String status, Instant timestamp,
                             String sessionId, String reason) {}
