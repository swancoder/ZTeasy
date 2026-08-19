package com.zte.gateway.governance;

import com.zte.gateway.audit.RequestLog;

import java.time.Instant;
import java.util.List;

/**
 * Combined governance snapshot (Stage 4, ADR-021) — {@code GET
 * /api/v1/admin/governance/report}, the Admin Console's "Export Report"
 * button. Deliberately just the two views the dashboard already shows
 * (nothing computed here that isn't already visible in the UI) rather than
 * a separately-formatted compliance document — a real ACAP {@code
 * evidence.report: monthly_compliance} would need its own aggregation
 * period/format; this is a plain, honest JSON snapshot of "what the
 * dashboard shows right now."
 */
public record GovernanceReport(Instant generatedAt, int windowHours, List<AgentActivitySummary> agentActivity,
                                List<RequestLog> outOfPolicyAttempts) {
}
