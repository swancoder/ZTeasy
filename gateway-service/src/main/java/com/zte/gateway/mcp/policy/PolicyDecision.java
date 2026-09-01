package com.zte.gateway.mcp.policy;

/**
 * Outcome of a {@link McpPolicyEngine} evaluation.
 *
 * <p>{@code HOLD} (Stage 1, ADR-019): the call is neither forwarded to the
 * backend nor rejected — it's parked pending a human decision (see {@code
 * com.zte.gateway.mcp.approval}). {@link #allowed()} is kept as a derived
 * convenience for call sites that only ever cared about the ALLOW/not-ALLOW
 * split before HOLD existed; new code should switch on {@link #outcome()}.
 */
public record PolicyDecision(Outcome outcome, String reason, String routeTo) {

    public enum Outcome { ALLOW, DENY, HOLD }

    /** Two-component form for the ALLOW/DENY sites that predate ADR-034's routing. */
    public PolicyDecision(Outcome outcome, String reason) {
        this(outcome, reason, null);
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(Outcome.ALLOW, "ok");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Outcome.DENY, reason);
    }

    public static PolicyDecision hold(String reason) {
        return new PolicyDecision(Outcome.HOLD, reason, null);
    }

    /**
     * A hold whose matched rule names who may decide it (ADR-034). {@code routeTo}
     * travels with the decision rather than being re-derived later, because by the
     * time the approval row is written the rule that matched is long out of scope.
     */
    public static PolicyDecision hold(String reason, String routeTo) {
        return new PolicyDecision(Outcome.HOLD, reason, routeTo);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }
}
