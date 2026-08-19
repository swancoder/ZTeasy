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
public record PolicyDecision(Outcome outcome, String reason) {

    public enum Outcome { ALLOW, DENY, HOLD }

    public static PolicyDecision allow() {
        return new PolicyDecision(Outcome.ALLOW, "ok");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Outcome.DENY, reason);
    }

    public static PolicyDecision hold(String reason) {
        return new PolicyDecision(Outcome.HOLD, reason);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }
}
