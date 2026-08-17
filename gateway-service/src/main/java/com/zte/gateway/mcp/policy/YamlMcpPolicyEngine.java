package com.zte.gateway.mcp.policy;

import com.zte.gateway.identity.IdentitySources;
import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.RuleEffect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link McpPolicyEngine} backed by the YAML {@code agentMcpToolCalls} rules
 * (ADR-011), replacing the {@code DummyMcpPolicyEngine} placeholder.
 *
 * <p>Satisfies ADR-009 (see also SPECS.md §5.4): {@link #evaluate} is synchronous and zero-I/O —
 * rule data is pre-loaded into {@link PolicyDefinitionStore}'s
 * {@code AtomicReference} snapshot, read here with a plain field access, never
 * fetched inline during the request path.
 */
@Component
public class YamlMcpPolicyEngine implements McpPolicyEngine {

    private final PolicyDefinitionStore policyDefinitionStore;
    private final PolicyMatcher policyMatcher;
    private final PolicyDefaultsProperties policyDefaults;

    public YamlMcpPolicyEngine(PolicyDefinitionStore policyDefinitionStore,
                                PolicyMatcher policyMatcher,
                                PolicyDefaultsProperties policyDefaults) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.policyMatcher = policyMatcher;
        this.policyDefaults = policyDefaults;
    }

    @Override
    public PolicyDecision evaluate(String agentId, String toolName, Map<String, Object> arguments) {
        if (agentId == null || agentId.isBlank()) {
            return PolicyDecision.deny("Unknown agent");
        }
        if (toolName == null || toolName.isBlank()) {
            return PolicyDecision.deny("Missing tool name");
        }

        PolicyEvaluation eval = policyMatcher.evaluate(
                policyDefinitionStore.current().agentMcpToolCalls(),
                IdentitySources.enrichClient(agentId), toolName, null, null);

        return switch (eval.outcome()) {
            case DENIED -> PolicyDecision.deny(
                    "Tool '" + toolName + "' denied by rule '" + eval.matchedRule().id() + "'");
            case ALLOWED -> PolicyDecision.allow();
            case NO_MATCH -> policyDefaults.getDefaultEffect() == RuleEffect.ALLOW
                    ? PolicyDecision.allow()
                    : PolicyDecision.deny(
                            "No policy grants agent '" + agentId + "' access to tool '" + toolName + "'");
        };
    }
}
