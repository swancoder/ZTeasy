package com.zte.gateway.policy.def;

/**
 * Result of {@link PolicyMatcher#evaluate}: distinguishes an explicit rule match
 * (ALLOWED/DENIED) from no rule matching at all (NO_MATCH) — callers decide how
 * to handle NO_MATCH themselves (fall back to the DB policy for users2service,
 * or the configured default effect for service2service/MCP), since the two
 * categories resolve "no match" differently.
 */
public record PolicyEvaluation(Outcome outcome, PolicyRule matchedRule, String reason) {

    public enum Outcome { ALLOWED, DENIED, NO_MATCH }

    public static PolicyEvaluation allowed(PolicyRule rule) {
        return new PolicyEvaluation(Outcome.ALLOWED, rule, "matched rule '" + rule.id() + "'");
    }

    public static PolicyEvaluation denied(PolicyRule rule) {
        return new PolicyEvaluation(Outcome.DENIED, rule, "matched rule '" + rule.id() + "'");
    }

    public static PolicyEvaluation noMatch() {
        return new PolicyEvaluation(Outcome.NO_MATCH, null, "no matching rule");
    }
}
