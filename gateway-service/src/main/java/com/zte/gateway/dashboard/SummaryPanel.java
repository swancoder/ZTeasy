package com.zte.gateway.dashboard;

/**
 * The shared KPI row every dashboard audience sees (Stage 29, ADR-029).
 *
 * <p>{@code agentsGoverned}/{@code agentsSeen}: agents that have an ACAP
 * profile, out of every agent that actually appeared in the audit trail —
 * a measure of reality, not of configuration.
 *
 * <p>Money is integer micro-euros ({@code spendMicros}); the UI divides.
 */
public record SummaryPanel(
        long agentsGoverned,
        long agentsSeen,
        long actionsInWindow,
        GateDecisions decisions,
        long awaitingApproval,
        long acapProfilesCurrent,
        long acapProfilesTotal,
        long acapProfilesOverdue,
        long spendMicros,
        long tokensTotal,
        int llmCalls
) {}
