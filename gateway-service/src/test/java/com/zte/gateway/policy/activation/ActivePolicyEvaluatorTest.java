package com.zte.gateway.policy.activation;

import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the activation overlay (Stage 31, ADR-031): a disabled rule
 * contributes nothing to the decision, and a disabled rule that would have
 * matched is surfaced to the caller.
 */
class ActivePolicyEvaluatorTest {

    private final PolicyMatcher matcher = new PolicyMatcher();

    private static final PolicyRule ALLOW_ADMIN =
            new PolicyRule("u2s-allow", RuleEffect.ALLOW, "ADMIN", "service-a", "/api/v1/service-a/**", "*", 0);
    private static final PolicyRule DENY_ALL =
            new PolicyRule("mcp-deny-delete", RuleEffect.DENY, "*", "delete*", null, null, 100);

    @Test
    void disabledAllowRule_evaluatesAsIfAbsent_andIsReportedAsInactiveMatch() {
        ActivePolicyEvaluator evaluator = TestActivation.withDisabled(matcher, "u2s-allow");

        ActivePolicyEvaluator.WithInactive result = evaluator.evaluateDetailed("users2service",
                List.of(ALLOW_ADMIN), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET", null);

        // The expected effect does NOT happen…
        assertThat(result.evaluation().outcome()).isEqualTo(PolicyEvaluation.Outcome.NO_MATCH);
        // …and the would-have-matched rule is reported, so the caller can record why.
        assertThat(result.inactiveMatches()).extracting(PolicyRule::id).containsExactly("u2s-allow");
    }

    @Test
    void disabledDenySafetyNet_noLongerBlocks_butTheMatchIsSurfaced() {
        PolicyRule allowDelete =
                new PolicyRule("mcp-allow-del", RuleEffect.ALLOW, "client:agent-a", "delete_thing", null, null, 10);
        ActivePolicyEvaluator evaluator = TestActivation.withDisabled(matcher, "mcp-deny-delete");

        ActivePolicyEvaluator.WithInactive result = evaluator.evaluateDetailed("agentMcpToolCalls",
                List.of(DENY_ALL, allowDelete), List.of("client:agent-a", "agent-a"),
                "delete_thing", null, null, null);

        assertThat(result.evaluation().outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
        assertThat(result.inactiveMatches()).extracting(PolicyRule::id).containsExactly("mcp-deny-delete");
    }

    @Test
    void enabledRules_behaveExactlyAsBeforeTheOverlayExisted() {
        ActivePolicyEvaluator evaluator = TestActivation.allActive(matcher);

        PolicyEvaluation eval = evaluator.evaluate("users2service",
                List.of(ALLOW_ADMIN), List.of("ADMIN"), "service-a", "/api/v1/service-a/hello", "GET");

        assertThat(eval.outcome()).isEqualTo(PolicyEvaluation.Outcome.ALLOWED);
        assertThat(eval.matchedRule().id()).isEqualTo("u2s-allow");
    }

    @Test
    void disabledRuleThatWouldNotHaveMatched_isNotReported() {
        ActivePolicyEvaluator evaluator = TestActivation.withDisabled(matcher, "u2s-allow");

        ActivePolicyEvaluator.WithInactive result = evaluator.evaluateDetailed("users2service",
                List.of(ALLOW_ADMIN), List.of("USER"), "service-a", "/api/v1/service-a/hello", "GET", null);

        assertThat(result.inactiveMatches()).isEmpty();
    }

    @Test
    void disabledHoldRule_callIsNotHeld_andTheHoldMatchIsSurfaced() {
        PolicyRule hold = new PolicyRule("mcp-hold-email", RuleEffect.ALLOW, "client:crm", "send_email", null, null, 0);
        ActivePolicyEvaluator evaluator = TestActivation.withDisabled(matcher, "mcp-hold-email");

        ActivePolicyEvaluator.HoldWithInactive result = evaluator.matchAnyHold("agentMcpToolHolds",
                List.of(hold), List.of("client:crm", "crm"), "send_email", null);

        assertThat(result.match()).isEmpty();
        assertThat(result.inactiveMatches()).extracting(PolicyRule::id).containsExactly("mcp-hold-email");
    }
}
