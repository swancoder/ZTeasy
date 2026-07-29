package com.zte.gateway.mcp.policy;

/**
 * Outcome of a {@link McpPolicyEngine} evaluation.
 */
public record PolicyDecision(boolean allowed, String reason) {

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "ok");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }
}
