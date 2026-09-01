package com.zte.gateway.policy.activation;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.PolicyRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Evaluation with the activation overlay applied (Stage 31, ADR-031).
 *
 * <p>Semantics: a disabled rule contributes <em>nothing</em> to the decision
 * — the evaluation runs over the active subset exactly as if the rule were
 * absent — but any disabled rule that <em>would</em> have matched is
 * surfaced: a {@code POLICY_INACTIVE_MATCH} audit line for every category,
 * and (for MCP, where the caller annotates its audit row) returned to the
 * caller. This is the honest middle ground: switching a rule off changes the
 * outcome, never the record of why.
 */
@Component
public class ActivePolicyEvaluator {

    private final PolicyMatcher matcher;
    private final PolicyActivationStore activationStore;

    public ActivePolicyEvaluator(PolicyMatcher matcher, PolicyActivationStore activationStore) {
        this.matcher = matcher;
        this.activationStore = activationStore;
    }

    /** REST categories (users2service / service2service): inactive hits are logged, evaluation returned. */
    public PolicyEvaluation evaluate(String category, List<PolicyRule> rules, List<String> sources,
                                      String target, String path, String method) {
        WithInactive result = evaluateDetailed(category, rules, sources, target, path, method, null);
        return result.evaluation();
    }

    /** MCP tool-call category: the caller also gets the inactive hits, to annotate its own audit row. */
    public WithInactive evaluateDetailed(String category, List<PolicyRule> rules, List<String> sources,
                                          String target, String path, String method, String mcpIdentifier) {
        PolicyEvaluation eval = matcher.evaluate(
                activationStore.active(rules), sources, target, path, method, mcpIdentifier);
        List<PolicyRule> inactiveHits = matcher.matching(
                activationStore.inactive(rules), sources, target, path, method, mcpIdentifier);
        String applied = eval.outcome().name();
        for (PolicyRule hit : inactiveHits) {
            ZteAuditLogger.policyInactiveMatch(category, hit.id(), hit.effect().name(),
                    String.join(",", sources), target, applied);
        }
        return new WithInactive(eval, inactiveHits);
    }

    /** agentMcpToolHolds: hold matching over the active subset, inactive hold hits logged and returned. */
    public HoldWithInactive matchAnyHold(String category, List<PolicyRule> rules, List<String> sources,
                                          String target, String mcpIdentifier) {
        Optional<PolicyRule> match = matcher.matchAny(activationStore.active(rules), sources, target, mcpIdentifier);
        List<PolicyRule> inactiveHits = matcher.matching(
                activationStore.inactive(rules), sources, target, null, null, mcpIdentifier);
        for (PolicyRule hit : inactiveHits) {
            ZteAuditLogger.policyInactiveMatch(category, hit.id(), "HOLD",
                    String.join(",", sources), target, match.isPresent() ? "HOLD" : "NOT_HELD");
        }
        return new HoldWithInactive(match, inactiveHits);
    }

    public record WithInactive(PolicyEvaluation evaluation, List<PolicyRule> inactiveMatches) {}

    public record HoldWithInactive(Optional<PolicyRule> match, List<PolicyRule> inactiveMatches) {}
}
