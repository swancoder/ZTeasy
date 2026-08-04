package com.zte.gateway.mcp.policy;

import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link YamlMcpPolicyEngine} — uses the real {@link PolicyMatcher}
 * (pure, no I/O) with a mocked {@link PolicyDefinitionStore} snapshot, matching how
 * {@code ZteAuthorizationFilterTest} mocks its DB collaborator.
 */
@ExtendWith(MockitoExtension.class)
class YamlMcpPolicyEngineTest {

    @Mock PolicyDefinitionStore store;

    private final PolicyMatcher matcher = new PolicyMatcher();
    private PolicyDefaultsProperties defaults;
    private YamlMcpPolicyEngine engine;

    @BeforeEach
    void setUp() {
        defaults = new PolicyDefaultsProperties();
        defaults.setDefaultEffect(RuleEffect.DENY);
        engine = new YamlMcpPolicyEngine(store, matcher, defaults);
    }

    private void withRules(PolicyRule... rules) {
        PolicyDocument doc = new PolicyDocument(1, List.of(), List.of(), List.of(rules));
        when(store.current()).thenReturn(doc);
    }

    @Test
    void explicitAllowRule_isAllowed() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void explicitDenyRule_isDenied() {
        withRules(new PolicyRule("d1", RuleEffect.DENY, "*", "delete*", null, null, 100));

        PolicyDecision decision = engine.evaluate("agent-a", "delete_deal", Map.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("d1");
    }

    @Test
    void noMatchingRule_defaultsToDeny() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0));

        PolicyDecision decision = engine.evaluate("agent-b", "get_deals", Map.of());

        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void noMatchingRule_withDefaultAllowConfigured_isAllowed() {
        defaults.setDefaultEffect(RuleEffect.ALLOW);
        withRules();

        PolicyDecision decision = engine.evaluate("agent-a", "get_contacts", Map.of());

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void blankAgentId_isDenied() {
        PolicyDecision decision = engine.evaluate("", "get_deals", Map.of());
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void blankToolName_isDenied() {
        PolicyDecision decision = engine.evaluate("agent-a", null, Map.of());
        assertThat(decision.allowed()).isFalse();
    }
}
