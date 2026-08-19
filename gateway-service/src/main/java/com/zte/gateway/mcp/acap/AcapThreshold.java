package com.zte.gateway.mcp.acap;

/**
 * One {@code thresholds[]} entry (Stage 6, ADR-022) — e.g. ACAP's {@code
 * followup_drafts_per_day} limit 30, {@code on_exceed: hold}.
 *
 * <p>{@code toolName} is a ZTeasy-specific addition, not part of the source
 * ACAP JSON — the real schema's {@code metric} name ({@code
 * "followup_drafts_per_day"}) doesn't mechanically derive the tool name it
 * counts ({@code draft_followup}) by any naming convention robust enough to
 * trust; rather than guess, this profile says so explicitly. {@code metric}
 * is kept as its own field purely for the human-readable label ACAP's own
 * monitoring/evidence framing uses.
 *
 * @param onExceed only {@code "hold"} is implemented (the only value the real ACAP example uses) — see {@code AcapScopeEvaluator#checkThresholds}
 */
public record AcapThreshold(String metric, String toolName, int limit, String onExceed) {
}
