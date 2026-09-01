package com.zte.gateway.mcp.acap;

import com.zte.gateway.mcp.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AcapScopeEvaluator} (Stage 3, ADR-020). */
class AcapScopeEvaluatorTest {

    private final AcapScopeEvaluator evaluator = new AcapScopeEvaluator(TestThresholdTracker.empty());

    private static final AcapProfile PROFILE = new AcapProfile(
            "crm-account-health-emea-01", "EMEA",
            new AcapScope(List.of(
                    new AcapReadGrant("contacts", List.of("name", "company", "lifecycle_stage")),
                    new AcapReadGrant("activities", List.of())), // empty fields = no field restriction
                    false));

    @Test
    void matchingTerritory_noFieldsRequested_noOpinion() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_contacts", Map.of("territory", "EMEA"));
        assertThat(result).isEmpty();
    }

    @Test
    void matchingTerritory_onlyAllowedFieldsRequested_noOpinion() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_contacts",
                Map.of("territory", "EMEA", "fields", List.of("name", "company")));
        assertThat(result).isEmpty();
    }

    @Test
    void wrongTerritory_isDenied() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_contacts", Map.of("territory", "NA"));
        assertThat(result).isPresent();
        assertThat(result.get().outcome()).isEqualTo(PolicyDecision.Outcome.DENY);
        assertThat(result.get().reason()).contains("read_outside_territory").contains("NA").contains("EMEA");
    }

    @Test
    void missingTerritoryArgument_isDenied() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_contacts", Map.of());
        assertThat(result).isPresent();
        assertThat(result.get().reason()).contains("read_outside_territory");
    }

    @Test
    void disallowedFieldRequested_isDenied() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_contacts",
                Map.of("territory", "EMEA", "fields", List.of("name", "id_number")));
        assertThat(result).isPresent();
        assertThat(result.get().reason()).contains("fields.deny").contains("id_number");
    }

    @Test
    void resourceWithNoFieldRestriction_anyFieldsAllowed() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_activities",
                Map.of("territory", "EMEA", "fields", List.of("anything", "goes")));
        assertThat(result).isEmpty();
    }

    @Test
    void resourceNotGranted_isDenied() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "read_deals", Map.of("territory", "EMEA"));
        assertThat(result).isPresent();
        assertThat(result.get().reason()).contains("deals");
    }

    @Test
    void exportTool_alwaysDenied_regardlessOfProfile() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "export_contacts", Map.of());
        assertThat(result).isPresent();
        assertThat(result.get().reason()).contains("bulk_export_contacts");
    }

    @Test
    void updateTool_readOnlyProfile_isDenied() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "update_deal", Map.of());
        assertThat(result).isPresent();
        assertThat(result.get().reason()).contains("change_record");
    }

    @Test
    void updateTool_writeAllowedProfile_noOpinion() {
        AcapProfile writeCapable = new AcapProfile("agent-x", "EMEA", new AcapScope(List.of(), true));
        Optional<PolicyDecision> result = evaluator.tighten(writeCapable, "update_deal", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void nonScopedTool_noOpinion_deferredToHoldMechanism() {
        Optional<PolicyDecision> result = evaluator.tighten(PROFILE, "send_email", Map.of());
        assertThat(result).isEmpty();
    }

    // ── checkThresholds (Stage 6, ADR-022) ──────────────────────────────────

    private static final AcapProfile PROFILE_WITH_THRESHOLD = new AcapProfile(
            "crm-account-health-emea-01", "EMEA", new AcapScope(List.of(), false),
            null, null,
            List.of(new AcapThreshold("followup_drafts_per_day", "draft_followup", 2, "hold")));

    @Test
    void underLimit_allowStaysAllow() {
        AcapScopeEvaluator eval = new AcapScopeEvaluator(TestThresholdTracker.empty());
        Optional<PolicyDecision> result = eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup",
                PolicyDecision.Outcome.ALLOW);
        assertThat(result).isEmpty();
    }

    @Test
    void exceedingLimit_escalatesAllowToHold() {
        AcapScopeEvaluator eval = new AcapScopeEvaluator(TestThresholdTracker.empty());
        eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup", PolicyDecision.Outcome.ALLOW); // 1
        eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup", PolicyDecision.Outcome.ALLOW); // 2, at limit
        Optional<PolicyDecision> result = eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup",
                PolicyDecision.Outcome.ALLOW); // 3, over limit

        assertThat(result).isPresent();
        assertThat(result.get().outcome()).isEqualTo(PolicyDecision.Outcome.HOLD);
        assertThat(result.get().reason()).contains("followup_drafts_per_day");
    }

    @Test
    void exceedingLimit_alreadyHeld_notReEscalated() {
        AcapScopeEvaluator eval = new AcapScopeEvaluator(TestThresholdTracker.empty());
        eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup", PolicyDecision.Outcome.ALLOW);
        eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup", PolicyDecision.Outcome.ALLOW);
        Optional<PolicyDecision> result = eval.checkThresholds(PROFILE_WITH_THRESHOLD, "draft_followup",
                PolicyDecision.Outcome.HOLD); // already held by agentMcpToolHolds — no further escalation needed

        assertThat(result).isEmpty();
    }

    @Test
    void nonMatchingToolName_noThresholdConsulted() {
        AcapScopeEvaluator eval = new AcapScopeEvaluator(TestThresholdTracker.empty());
        Optional<PolicyDecision> result = eval.checkThresholds(PROFILE_WITH_THRESHOLD, "read_contacts",
                PolicyDecision.Outcome.ALLOW);
        assertThat(result).isEmpty();
    }

    @Test
    void noThresholdsConfigured_noOpinion() {
        AcapScopeEvaluator eval = new AcapScopeEvaluator(TestThresholdTracker.empty());
        Optional<PolicyDecision> result = eval.checkThresholds(PROFILE, "draft_followup", PolicyDecision.Outcome.ALLOW);
        assertThat(result).isEmpty();
    }
}
