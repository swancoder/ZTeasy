package com.zte.gateway.mcp.acap;

import java.util.List;

/**
 * A per-agent ACAP scope profile (Stage 3, ADR-020; agent metadata and
 * thresholds added Stage 6, ADR-022) — additive, opt-in enrichment on top of
 * {@code agentMcpToolCalls}/{@code agentMcpToolHolds} (ADR-011/ADR-019): an
 * agent with no profile here is governed by those two alone, exactly as
 * before Stage 3. An agent *with* a profile gets a further, argument-aware
 * tightening pass ({@code AcapScopeEvaluator}) — territory/field/write/bulk
 * checks the coarse, tool-name-only rule engine has no way to express, plus
 * (Stage 6) a per-agent-per-metric usage threshold that can escalate an
 * ALLOW to HOLD.
 *
 * <p>Binds directly from one YAML file per agent under {@code
 * acap-profiles/} (filename is not significant — {@code agentId} is read
 * from the document itself), a deliberately simplified subset of the source
 * ACAP schema (see {@code examples-from-vlad/acap-crm-account-health.json}):
 * no {@code platforms}/{@code hold}/{@code never}/{@code audit}/{@code
 * evidence}/{@code deny_response} — {@code hold} is already ADR-019's {@code
 * agentMcpToolHolds}'s job; {@code never} is realized as fixed reason labels
 * in {@code AcapScopeEvaluator}, not separately-loaded data (see its
 * Javadoc); the rest have no consumer in this codebase yet. {@code agent}/
 * {@code risk}/{@code thresholds} are display-only/threshold-tracking as of
 * Stage 6 — none of them are enforced beyond the threshold-to-HOLD
 * escalation itself.
 *
 * @param agentId    matches the calling agent's {@code azp}/{@code IdentitySources} identity — required. Kept as its own top-level field (unlike the source ACAP JSON's nested {@code agent.id}) since it's the primary key {@link AcapProfileStore} indexes by and every lookup site already keys on — nesting it under {@code agent} would ripple through more code for no benefit.
 * @param territory  the agent's single assigned territory (ACAP's {@code assigned.territory}) — required
 * @param scope      read/write grants; {@code null} (an entirely empty profile) behaves as "reads nothing, writes nothing"
 * @param agent      display metadata (name/client/owner/deploymentDate/reauthDue) — optional, Admin Console display only
 * @param risk       EU AI Act classification + internal tier — optional, display only
 * @param thresholds per-agent-per-metric usage limits that can escalate ALLOW to HOLD — optional, empty means none
 */
public record AcapProfile(String agentId, String territory, AcapScope scope, AcapAgentInfo agent, AcapRisk risk,
                           List<AcapThreshold> thresholds) {

    /** Convenience constructor for every call site written before Stage 6's agent/risk/thresholds fields existed. */
    public AcapProfile(String agentId, String territory, AcapScope scope) {
        this(agentId, territory, scope, null, null, List.of());
    }

    public List<AcapReadGrant> readGrants() {
        return scope == null ? List.of() : scope.read();
    }

    public boolean writeAllowed() {
        return scope != null && scope.writeAllowed();
    }

    public List<AcapThreshold> thresholds() {
        return thresholds == null ? List.of() : thresholds;
    }
}
