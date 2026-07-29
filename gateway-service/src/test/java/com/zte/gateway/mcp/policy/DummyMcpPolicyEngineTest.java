package com.zte.gateway.mcp.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DummyMcpPolicyEngine}.
 */
class DummyMcpPolicyEngineTest {

    private final DummyMcpPolicyEngine engine = new DummyMcpPolicyEngine();

    @Test
    void allowsAnOrdinaryTool() {
        PolicyDecision decision = engine.evaluate("agent-1", "get_contacts", Map.of());
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void deniesExactDenylistMatch() {
        PolicyDecision decision = engine.evaluate("agent-1", "export_all_data", Map.of());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("export_all_data");
    }

    @Test
    void deniesDestructivePatternCaseInsensitively() {
        PolicyDecision decision = engine.evaluate("agent-1", "DELETE_Record", Map.of());
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void deniesMissingToolName() {
        PolicyDecision decision = engine.evaluate("agent-1", null, Map.of());
        assertThat(decision.allowed()).isFalse();
    }
}
