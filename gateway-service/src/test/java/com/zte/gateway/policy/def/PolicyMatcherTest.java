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
}
