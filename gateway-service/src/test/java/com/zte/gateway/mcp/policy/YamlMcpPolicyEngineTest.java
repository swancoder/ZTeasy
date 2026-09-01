package com.zte.gateway.mcp.policy;

import com.zte.gateway.policy.activation.TestActivation;
import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapReadGrant;
import com.zte.gateway.mcp.acap.AcapScope;
import com.zte.gateway.mcp.acap.AcapScopeEvaluator;
import com.zte.gateway.mcp.acap.AcapThreshold;
import com.zte.gateway.mcp.acap.AcapThresholdTracker;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link YamlMcpPolicyEngine} — uses the real {@link PolicyMatcher}
 * (pure, no I/O) with a mocked {@link PolicyDefinitionStore} snapshot, matching how
 * {@code ZteAuthorizationFilterTest} mocks its DB collaborator. {@link AcapProfileStore}
 * is likewise mocked; unstubbed {@code find(...)} defaults to {@code Optional.empty()}
 * (Mockito's own default for Optional-returning methods), so every test before the
 * "Stage 3 / ACAP" section below exercises the no-profile path unchanged.
 */
@ExtendWith(MockitoExtension.class)
class YamlMcpPolicyEngineTest {

    private static final String MCP_BACKEND_NAME = "hubspot-mcp";

    @Mock PolicyDefinitionStore store;
    @Mock AcapProfileStore acapProfileStore;

    private final PolicyMatcher matcher = new PolicyMatcher();
    private final AcapScopeEvaluator acapScopeEvaluator = new AcapScopeEvaluator(new AcapThresholdTracker());
    private PolicyDefaultsProperties defaults;
    private YamlMcpPolicyEngine engine;

    @BeforeEach
    void setUp() {
        defaults = new PolicyDefaultsProperties();
        defaults.setDefaultEffect(RuleEffect.DENY);
        engine = new YamlMcpPolicyEngine(store, TestActivation.allActive(matcher), defaults, acapProfileStore, acapScopeEvaluator, MCP_BACKEND_NAME);
    }

    private void withRules(PolicyRule... rules) {
        PolicyDocument doc = new PolicyDocument(1, List.of(), List.of(), List.of(rules));
        when(store.current()).thenReturn(doc);
    }

    private void withRulesAndHolds(List<PolicyRule> calls, List<PolicyRule> holds) {
        PolicyDocument doc = new PolicyDocument(1, List.of(), List.of(), calls, holds);
        when(store.current()).thenReturn(doc);
    }

    @Test
    void explicitAllowRule_isAllowed() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.allowed()).isTrue();
    }

    /**
     * ADR-015: a {@code client:}-prefixed source matches identically to the
     * bare client-id form.
     */
    @Test
    void urnPrefixedAllowRule_isAllowed() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:agent-a", "get_deals", null, null, 0));

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

    /** Stage 1 (ADR-019): a call that would otherwise ALLOW is HELD when it also matches an agentMcpToolHolds rule. */
    @Test
    void allowedButHeldTool_isHeld() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "send_email", null, null, 0);
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "send_email", null, null, 0);
        withRulesAndHolds(List.of(allow), List.of(hold));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "send_email", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.HOLD);
        assertThat(decision.reason()).contains("h1");
    }

    /** A hold rule for a *different* agent/tool must not affect this call. */
    @Test
    void allowedTool_notMatchingAnyHoldRule_isPlainAllow() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "client:agent-a", "get_deals", null, null, 0);
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "send_email", null, null, 0);
        withRulesAndHolds(List.of(allow), List.of(hold));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    /** A DENY from agentMcpToolCalls wins outright — a matching hold rule never loosens it back to HOLD. */
    @Test
    void deniedTool_isNeverDowngradedToHold_evenIfHoldRuleMatches() {
        PolicyRule deny = new PolicyRule("d1", RuleEffect.DENY, "*", "delete*", null, null, 100);
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "*", "delete*", null, null, 0);
        withRulesAndHolds(List.of(deny), List.of(hold));

        PolicyDecision decision = engine.evaluate("agent-a", "delete_deal", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
    }

    // ── Stage 3 / ACAP (ADR-020): argument/field-level tightening ──────────

    private static final AcapProfile EMEA_READ_ONLY_PROFILE = new AcapProfile(
            "crm-account-health-emea-01", "EMEA",
            new AcapScope(List.of(new AcapReadGrant("contacts",
                    List.of("name", "company", "lifecycle_stage", "last_activity", "deal_ids"))), false));

    @Test
    void allowedByCoarseRule_wrongTerritory_isTightenedToDeny() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "read_contacts", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(EMEA_READ_ONLY_PROFILE));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "read_contacts",
                Map.of("territory", "NA"));

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("read_outside_territory");
    }

    @Test
    void allowedByCoarseRule_disallowedField_isTightenedToDeny() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "read_contacts", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(EMEA_READ_ONLY_PROFILE));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "read_contacts",
                Map.of("territory", "EMEA", "fields", List.of("id_number")));

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("fields.deny");
    }

    @Test
    void allowedByCoarseRule_correctTerritoryAndFields_staysAllowed() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "read_contacts", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(EMEA_READ_ONLY_PROFILE));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "read_contacts",
                Map.of("territory", "EMEA", "fields", List.of("name", "company")));

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    @Test
    void allowedByCoarseRule_writeToolUnderReadOnlyProfile_isTightenedToDeny() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "update_deal", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(EMEA_READ_ONLY_PROFILE));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "update_deal", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("change_record");
    }

    @Test
    void allowedByCoarseRule_exportTool_isTightenedToDenyEvenIfSomehowGranted() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "export_contacts", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(EMEA_READ_ONLY_PROFILE));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "export_contacts", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("bulk_export_contacts");
    }

    @Test
    void deniedByCoarseRule_neverConsultsAcapProfile() {
        withRules(new PolicyRule("d1", RuleEffect.DENY, "*", "read_contacts", null, null, 100));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "read_contacts",
                Map.of("territory", "EMEA"));

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        verifyNoInteractions(acapProfileStore);
    }

    @Test
    void agentWithNoAcapProfile_coarseDecisionStandsUntightened() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:agent-a", "get_deals", null, null, 0));
        when(acapProfileStore.find("agent-a")).thenReturn(Optional.empty());

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    // ── Stage 6 / ACAP thresholds (ADR-022): usage-based ALLOW->HOLD escalation ──

    @Test
    void allowedByCoarseRule_thresholdExceeded_isEscalatedToHold() {
        AcapProfile profileWithThreshold = new AcapProfile("crm-account-health-emea-01", "EMEA",
                new AcapScope(List.of(), false), null, null,
                List.of(new AcapThreshold("followup_drafts_per_day", "draft_followup", 1, "hold")));
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "draft_followup", null, null, 0));
        when(acapProfileStore.find("crm-account-health-emea-01")).thenReturn(Optional.of(profileWithThreshold));

        engine.evaluate("crm-account-health-emea-01", "draft_followup", Map.of()); // 1st call, at limit
        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "draft_followup", Map.of()); // 2nd, over

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.HOLD);
        assertThat(decision.reason()).contains("followup_drafts_per_day");
    }

    @Test
    void deniedByCoarseRule_neverConsultsThresholds() {
        withRules(new PolicyRule("d1", RuleEffect.DENY, "*", "draft_followup", null, null, 100));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "draft_followup", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        verifyNoInteractions(acapProfileStore);
    }

    // ── mcpTarget (ADR-023): a rule scoped to a different backend must not apply ──

    @Test
    void ruleWithNoMcpTarget_matchesRegardlessOfConfiguredBackend() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, null));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    @Test
    void ruleScopedToConfiguredBackend_matches() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, MCP_BACKEND_NAME));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    @Test
    void ruleScopedToDifferentBackend_isIgnored_fallsThroughToDefaultDeny() {
        withRules(new PolicyRule("a1", RuleEffect.ALLOW, "agent-a", "get_deals", null, null, 0, "some-other-mcp"));

        PolicyDecision decision = engine.evaluate("agent-a", "get_deals", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
    }

    @Test
    void holdRuleScopedToDifferentBackend_isIgnored() {
        PolicyRule allow = new PolicyRule("a1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "send_email", null, null, 0, null);
        PolicyRule hold = new PolicyRule("h1", RuleEffect.ALLOW, "client:crm-account-health-emea-01",
                "send_email", null, null, 0, "some-other-mcp");
        withRulesAndHolds(List.of(allow), List.of(hold));

        PolicyDecision decision = engine.evaluate("crm-account-health-emea-01", "send_email", Map.of());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }
}
