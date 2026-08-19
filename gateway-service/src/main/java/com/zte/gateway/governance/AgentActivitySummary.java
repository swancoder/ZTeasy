package com.zte.gateway.governance;

import java.time.Instant;

/**
 * Per-agent ALLOW/DENY/HOLD counts over a window (Stage 4, ADR-021) — the
 * governance dashboard's per-agent activity table, matching the shape of
 * ACAP's own {@code evidence.board_view} ({@code allowed}, {@code activity},
 * {@code out_of_policy_attempts}) at agent granularity.
 *
 * @param lastActivity the most recent MCP row's timestamp for this agent (any decision), never null for a summary that exists at all
 */
public record AgentActivitySummary(String agentId, long allowCount, long denyCount, long holdCount, Instant lastActivity) {
}
