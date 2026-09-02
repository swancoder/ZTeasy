package com.zte.gateway.mcp.policy;

import com.zte.gateway.identity.IdentitySources;
import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapScopeEvaluator;
import com.zte.gateway.mcp.acap.lifecycle.AcapLifecycleState;
import com.zte.gateway.mcp.acap.lifecycle.AcapLifecycleStore;
import com.zte.gateway.policy.activation.ActivePolicyEvaluator;
import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.RuleEffect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link McpPolicyEngine} backed by the YAML {@code agentMcpToolCalls} rules
 * (ADR-011), replacing the {@code DummyMcpPolicyEngine} placeholder.
 *
 * <p>Satisfies ADR-009 (see also SPECS.md §5.4): {@link #evaluate} is synchronous and zero-I/O —
 * rule data is pre-loaded into {@link PolicyDefinitionStore}'s
 * {@code AtomicReference} snapshot, read here with a plain field access, never
 * fetched inline during the request path.
 *
 * <p>{@code mcpBackendName} (ADR-023) is the configured {@code mcp-backend.name}
 * — passed to every {@link PolicyMatcher} call so an {@code agentMcpToolCalls}/
 * {@code agentMcpToolHolds} rule that names a specific {@code mcpTarget} only
 * applies while the gateway is actually pointed at that backend, rather than
 * silently keeping a stale grant alive if the backend is later swapped out.
 */
@Component
public class YamlMcpPolicyEngine implements McpPolicyEngine {

    private final PolicyDefinitionStore policyDefinitionStore;
    private final ActivePolicyEvaluator activeEvaluator;
    private final PolicyDefaultsProperties policyDefaults;
    private final AcapProfileStore acapProfileStore;
    private final AcapScopeEvaluator acapScopeEvaluator;
    private final AcapLifecycleStore lifecycleStore;
    private final String mcpBackendName;

    public YamlMcpPolicyEngine(PolicyDefinitionStore policyDefinitionStore,
                                ActivePolicyEvaluator activeEvaluator,
                                PolicyDefaultsProperties policyDefaults,
                                AcapProfileStore acapProfileStore,
                                AcapScopeEvaluator acapScopeEvaluator,
                                AcapLifecycleStore lifecycleStore,
                                @Value("${mcp-backend.name:hubspot-mcp}") String mcpBackendName) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.activeEvaluator = activeEvaluator;
        this.policyDefaults = policyDefaults;
        this.acapProfileStore = acapProfileStore;
        this.acapScopeEvaluator = acapScopeEvaluator;
        this.lifecycleStore = lifecycleStore;
        this.mcpBackendName = mcpBackendName;
    }

    @Override
    public PolicyDecision evaluate(String agentId, String toolName, Map<String, Object> arguments) {
        if (agentId == null || agentId.isBlank()) {
            return PolicyDecision.deny("Unknown agent");
        }
        return evaluate(McpCaller.client(agentId), toolName, arguments);
    }

    @Override
    public PolicyDecision evaluate(McpCaller caller, String toolName, Map<String, Object> arguments) {
        if (caller == null || caller.id() == null || caller.id().isBlank()) {
            return PolicyDecision.deny("Unknown caller");
        }
        if (toolName == null || toolName.isBlank()) {
            return PolicyDecision.deny("Missing tool name");
        }
        String agentId = caller.id();

        // Stage 32 (ADR-032): lifecycle gate before any rule work — a
        // suspended or retired agent is denied outright, with the state named
        // so the refusal is attributable to an operator's decision, not policy.
        //
        // Stage 39 (ADR-039): the lifecycle belongs to whichever ACAP profile
        // governs this caller, which for a person is found by role — so a
        // suspended "role:SALES_EMEA" profile stops every human it covers, and a
        // caller with no profile at all has no lifecycle to be suspended.
        // An agent's lifecycle is keyed by its own id, exactly as before. A person's
        // is keyed by whichever profile covers them — usually a role — so suspending
        // "role:SALES_EMEA" stops everyone it governs, and a person no profile covers
        // has no lifecycle to suspend.
        String lifecycleKey = caller.human()
                ? acapProfileStore.findKey(caller.acapKeys()).orElse(null)
                : agentId;
        if (lifecycleKey != null) {
            String lifecycleStatus = lifecycleStore.status(lifecycleKey);
            if (!AcapLifecycleState.ACTIVE.equals(lifecycleStatus)) {
                return PolicyDecision.deny("Agent '" + lifecycleKey + "' is " + lifecycleStatus.toLowerCase()
                        + " (ACAP lifecycle) — every call is refused until an operator reactivates it");
            }
        }

        List<String> sources = caller.sources();
        // Stage 31 (ADR-031): evaluated over the ACTIVE subset; disabled rules
        // that would have matched are logged and annotated onto the decision's
        // reason so the audit row records why the outcome differs from the file.
        ActivePolicyEvaluator.WithInactive detailed = activeEvaluator.evaluateDetailed("agentMcpToolCalls",
                policyDefinitionStore.current().agentMcpToolCalls(), sources, toolName, null, null, mcpBackendName);
        PolicyEvaluation eval = detailed.evaluation();

        PolicyDecision decision = switch (eval.outcome()) {
            case DENIED -> PolicyDecision.deny(
                    "Tool '" + toolName + "' denied by rule '" + eval.matchedRule().id() + "'");
            case ALLOWED -> checkHold(sources, toolName);
            case NO_MATCH -> policyDefaults.getDefaultEffect() == RuleEffect.ALLOW
                    ? checkHold(sources, toolName)
                    // ADR-039: say "user" for a person. "No policy grants agent 'zte-admin'"
                    // reads like a system fault to the human it is refusing, and the whole
                    // value of a refusal is that the person it lands on understands it.
                    : PolicyDecision.deny("No policy grants " + (caller.human() ? "user" : "agent")
                            + " '" + agentId + "' access to tool '" + toolName + "'");
        };

        return tightenViaAcapProfile(annotateInactive(decision, detailed.inactiveMatches()), caller, toolName, arguments);
    }

    /**
     * Stage 31 (ADR-031): fold "rule X matched but is switched off" into the
     * decision's reason, which {@code McpProxyHandler} already writes into the
     * audit row's message — so the trail explains why the outcome differs from
     * what the YAML alone would produce, without a new column or row type.
     */
    private PolicyDecision annotateInactive(PolicyDecision decision, List<com.zte.gateway.policy.def.PolicyRule> inactive) {
        if (inactive.isEmpty()) {
            return decision;
        }
        String note = "Inactive rule(s) matched but did not apply: "
                + inactive.stream().map(r -> r.id() + " (" + r.effect() + ")").reduce((a, b) -> a + ", " + b).orElse("");
        String reason = decision.reason() == null || decision.reason().isBlank()
                ? note
                : decision.reason() + ". " + note;
        return new PolicyDecision(decision.outcome(), reason);
    }

    /**
     * Stage 3 (ADR-020): an agent with an {@link AcapProfile} gets a further,
     * argument-aware tightening pass on top of the coarse decision above — a
     * DENY is already final and skips this entirely (this layer only ever
     * tightens, never loosens); an ALLOW/HOLD may still be downgraded to DENY
     * (territory mismatch, disallowed field, unscoped write, bulk/export).
     *
     * <p>Stage 6 (ADR-022): if scope tightening didn't already produce a
     * DENY, a per-agent-per-metric usage threshold may still escalate an
     * ALLOW to HOLD ({@code followup_drafts_per_day}-style limits).
     */
    private PolicyDecision tightenViaAcapProfile(PolicyDecision decision, McpCaller caller, String toolName,
                                                  Map<String, Object> arguments) {
        if (decision.outcome() == PolicyDecision.Outcome.DENY) {
            return decision;
        }
        String agentId = caller.id();
        // Single-key lookup for an agent keeps that path byte-for-byte what ADR-020
        // specified; the ordered lookup is only reached for a person.
        Optional<AcapProfile> profile = caller.human()
                ? acapProfileStore.find(caller.acapKeys())
                : acapProfileStore.find(agentId);
        if (profile.isEmpty()) {
            return decision;
        }
        Optional<PolicyDecision> scopeDecision = acapScopeEvaluator.tighten(profile.get(), toolName, arguments);
        if (scopeDecision.isPresent()) {
            return scopeDecision.get();
        }
        PolicyDecision afterThresholds =
                acapScopeEvaluator.checkThresholds(profile.get(), toolName, decision.outcome()).orElse(decision);

        // Stage 32 (ADR-032, amending ADR-022's display-only posture): an
        // ACTIVE agent whose re-authorization is overdue keeps working, but
        // every ALLOW goes through a human — supervision, not a stoppage.
        if (afterThresholds.outcome() == PolicyDecision.Outcome.ALLOW
                && lifecycleStore.isReauthOverdue(profile.get())) {
            String due = lifecycleStore.effectiveReauthDue(profile.get()).map(Object::toString).orElse("?");
            // No routeTo: this hold comes from the agent's lifecycle state, not from a
            // rule that could name an approver, so it stays open to any interactive user.
            return PolicyDecision.hold("Held: agent '" + agentId + "'s re-authorization has been overdue since "
                    + due + " (ACAP lifecycle) — approve to proceed, or re-authorize the agent");
        }
        return afterThresholds;
    }

    /**
     * Stage 1 (ADR-019): a call the coarse {@code agentMcpToolCalls} check
     * would otherwise ALLOW may still be held for a human decision, per {@code
     * agentMcpToolHolds} — checked as a separate pass via {@link
     * PolicyMatcher#matchAny}, never able to loosen a DENY into an ALLOW.
     */
    private PolicyDecision checkHold(List<String> sources, String toolName) {
        ActivePolicyEvaluator.HoldWithInactive holds = activeEvaluator.matchAnyHold("agentMcpToolHolds",
                policyDefinitionStore.current().agentMcpToolHolds(), sources, toolName, mcpBackendName);
        PolicyDecision decision = holds.match()
                .map(rule -> PolicyDecision.hold(
                        "Tool '" + toolName + "' held for human approval by rule '" + rule.id() + "'",
                        rule.routeTo()))
                .orElseGet(PolicyDecision::allow);
        return annotateInactive(decision, holds.inactiveMatches());
    }
}
