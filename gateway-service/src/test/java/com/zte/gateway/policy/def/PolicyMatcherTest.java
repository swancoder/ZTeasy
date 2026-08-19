package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PolicyMatcher}.
 */
class PolicyMatcherTest {

    private final PolicyMatcher matcher = new PolicyMatcher();

    @Test
    void noRules_isNoMatch() {
        PolicyEvaluation eval = matcher.evaluate(List.of(), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET");
        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
    }

    @Test
    void matchingAllowRule_isAllowed() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "service-a", "/**", "*", 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
        assertThat(eval.matchedRule().id()).isEqualTo("a1");
    }

    @Test
    void denyAlwaysWinsOverAllow_regardlessOfPriorityOrOrder() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "*", "service-a", null, null, 1000);
        PolicyRule deny  = new PolicyRule("d1", RuleEffect.DENY,  "*", "service-a", null, null, 0);

        PolicyEvaluation eval = matcher.evaluate(List.of(allow, deny), List.of("ADMIN"), "service-a", "/x", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.DENIED);
        assertThat(eval.matchedRule().id()).isEqualTo("d1");
    }

    @Test
    void higherPriorityWinsAmongSameEffect() {
        PolicyRule low  = new PolicyRule("low",  RuleEffect.ALLOW, "*", "service-a", null, null, 1);
        PolicyRule high = new PolicyRule("high", RuleEffect.ALLOW, "*", "service-a", null, null, 5);

        PolicyEvaluation eval = matcher.evaluate(List.of(low, high), List.of("ADMIN"), "service-a", "/x", "GET");

        assertThat(eval.matchedRule().id()).isEqualTo("high");
    }

    @Test
    void wildcardSourceMatchesAnyCaller() {
        PolicyRule deny = new PolicyRule("d1", RuleEffect.DENY, "*", "delete*", null, null, 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(deny), List.of("agent-a"), "delete_deal", null, null);

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.DENIED);
    }

    @Test
    void anyOfMultipleRoles_canMatch() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "service-a", null, null, 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("USER", "ADMIN"), "service-a", "/x", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
    }

    @Test
    void pathPatternRestrictsMatch() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "service-a", "/api/v1/service-a/admin/**", "*", 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
    }

    @Test
    void methodsRestrictsMatch() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "service-a", "/**", "POST", 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
    }

    @Test
    void nullPathAndMethod_matchPathAgnosticRule() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("agent-a"), "get_deals", null, null);

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
    }

    // ── matchAny (Stage 1, ADR-019 — agentMcpToolHolds) ────────────────────

    @Test
    void matchAny_noRules_isEmpty() {
        assertThat(matcher.matchAny(List.of(), List.of("agent-a"), "send_email")).isEmpty();
    }

    @Test
    void matchAny_matchingRule_isPresent() {
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "agent-a", "send_email", null, null, 0);
        assertThat(matcher.matchAny(List.of(hold), List.of("agent-a"), "send_email"))
                .map(PolicyRule::id).contains("h1");
    }

    @Test
    void matchAny_nonMatchingTarget_isEmpty() {
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "agent-a", "send_email", null, null, 0);
        assertThat(matcher.matchAny(List.of(hold), List.of("agent-a"), "get_deals")).isEmpty();
    }

    @Test
    void matchAny_higherPriorityWins() {
        PolicyRule low = new PolicyRule("low", RuleEffect.ALLOW, "*", "send_email", null, null, 1);
        PolicyRule high = new PolicyRule("high", RuleEffect.ALLOW, "*", "send_email", null, null, 5);
        assertThat(matcher.matchAny(List.of(low, high), List.of("agent-a"), "send_email"))
                .map(PolicyRule::id).contains("high");
    }

    // ── mcpTarget (ADR-023) ──────────────────────────────────────────────────

    @Test
    void evaluate_noMcpTarget_matchesAnyBackend() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, null);
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("agent-a"), "get_deals", null, null, "hubspot-mcp");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
    }

    @Test
    void evaluate_matchingMcpTarget_matches() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, "hubspot-mcp");
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("agent-a"), "get_deals", null, null, "hubspot-mcp");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
    }

    @Test
    void evaluate_mismatchedMcpTarget_isNoMatch() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, "salesforce-mcp");
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("agent-a"), "get_deals", null, null, "hubspot-mcp");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
    }

    @Test
    void evaluate_fiveArgOverload_mcpScopedRule_failsToMatch() {
        // The REST authorization filters call the 5-arg overload (no MCP identifier to
        // check against) — a rule scoped to an mcpTarget therefore never matches via
        // this overload, mirroring pathPattern/methods' own existing precedent: a
        // constraint with no value to check against in the current call context fails
        // to match rather than being silently ignored. In practice this only matters
        // if an operator mistakenly sets mcpTarget on a users2service/service2service
        // rule — mcpTarget is documented as agentMcpToolCalls/agentMcpToolHolds-only.
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, "hubspot-mcp");
        PolicyEvaluation eval = matcher.evaluate(List.of(allow), List.of("agent-a"), "get_deals", null, null);

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
    }

    @Test
    void matchAny_mismatchedMcpTarget_isEmpty() {
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "agent-a", "send_email", null, null, 0, "salesforce-mcp");
        assertThat(matcher.matchAny(List.of(hold), List.of("agent-a"), "send_email", "hubspot-mcp")).isEmpty();
    }

    @Test
    void matchAny_matchingMcpTarget_isPresent() {
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "agent-a", "send_email", null, null, 0, "hubspot-mcp");
        assertThat(matcher.matchAny(List.of(hold), List.of("agent-a"), "send_email", "hubspot-mcp"))
                .map(PolicyRule::id).contains("h1");
    }
}
