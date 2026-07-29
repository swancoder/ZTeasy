package com.zte.gateway.mcp.audit;

import java.time.Instant;

/**
 * A single MCP tool-invocation audit record.
 *
 * <p>Field set matches what a Time-Series DB (e.g. InfluxDB) would index on:
 * {@code processId}/{@code agentId}/{@code toolName} as tags, {@code status} as
 * a field, {@code timestamp} as the point time.
 */
public record McpAuditEvent(String processId, String agentId, String toolName, String status, Instant timestamp) {}
