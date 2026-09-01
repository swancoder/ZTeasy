package com.zte.gateway.mcp.acap.lifecycle;

import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapReadGrant;
import com.zte.gateway.mcp.acap.AcapScope;
import com.zte.gateway.mcp.acap.AcapScopeEvaluator;
import com.zte.gateway.mcp.acap.TestThresholdTracker;
import com.zte.gateway.mcp.policy.PolicyDecision;
import com.zte.gateway.mcp.policy.YamlMcpPolicyEngine;
import com.zte.gateway.policy.activation.TestActivation;
import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle enforcement (Stage 32, ADR-032): suspension denies outright, and
 * an overdue re-authorization escalates ALLOW to HOLD — the change that
 * amends ADR-022's display-only posture.
 */
class AcapLifecycleEnforcementTest {

    private static final String AGENT = "crm-account-health-emea-01";
    private static final String TOOL = "read_contacts";

    private static final AcapProfile PROFILE = new AcapProfile(AGENT, "EMEA",
            new AcapScope(List.of(new AcapReadGrant("contacts", List.of("name"))), false));

    private YamlMcpPolicyEngine engine(AcapLifecycleStore lifecycle) {
        PolicyDefinitionStore store = Mockito.mock(PolicyDefinitionStore.class);
        Mockito.when(store.current()).thenReturn(new PolicyDocument(1, List.of(), List.of(),
                List.of(new PolicyRule("allow", RuleEffect.ALLOW, "client:" + AGENT, TOOL, null, null, 10)),
                List.of()));
        AcapProfileStore profiles = Mockito.mock(AcapProfileStore.class);
        Mockito.lenient().when(profiles.find(AGENT)).thenReturn(Optional.of(PROFILE));
        return new YamlMcpPolicyEngine(store, TestActivation.allActive(new PolicyMatcher()),
                new PolicyDefaultsProperties(), profiles,
                new AcapScopeEvaluator(TestThresholdTracker.empty()), lifecycle, "hubspot-mcp");
    }

    private static Map<String, Object> args() {
        return Map.of("territory", "EMEA", "fields", List.of("name"));
    }

    @Test
    void activeAgent_withNoLifecycleRow_behavesExactlyAsBefore() {
        PolicyDecision decision = engine(TestLifecycle.empty()).evaluate(AGENT, TOOL, args());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    @Test
    void suspendedAgent_everyCallIsDenied_withTheLifecycleReasonNamed() {
        PolicyDecision decision = engine(TestLifecycle.withStatus(AGENT, AcapLifecycleState.SUSPENDED))
                .evaluate(AGENT, TOOL, args());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("suspended").contains("ACAP lifecycle");
    }

    @Test
    void retiredAgent_isDeniedToo() {
        PolicyDecision decision = engine(TestLifecycle.withStatus(AGENT, AcapLifecycleState.RETIRED))
                .evaluate(AGENT, TOOL, args());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("retired");
    }

    @Test
    void overdueReauthorization_escalatesAllowToHold_ratherThanBlocking() {
        PolicyDecision decision = engine(TestLifecycle.withReauthDue(AGENT, LocalDate.now().minusDays(1)))
                .evaluate(AGENT, TOOL, args());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.HOLD);
        assertThat(decision.reason()).contains("overdue");
    }

    @Test
    void futureReauthorizationDate_doesNotEscalate() {
        PolicyDecision decision = engine(TestLifecycle.withReauthDue(AGENT, LocalDate.now().plusDays(30)))
                .evaluate(AGENT, TOOL, args());

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.ALLOW);
    }

    /** A denied call stays denied — the lifecycle layer never loosens a refusal. */
    @Test
    void overdueAgent_deniedByScope_staysDenied() {
        PolicyDecision decision = engine(TestLifecycle.withReauthDue(AGENT, LocalDate.now().minusDays(1)))
                .evaluate(AGENT, TOOL, Map.of("territory", "NA"));

        assertThat(decision.outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
    }
}
