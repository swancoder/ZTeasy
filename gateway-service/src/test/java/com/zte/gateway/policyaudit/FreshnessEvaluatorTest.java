package com.zte.gateway.policyaudit;

import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for finding freshness (Stage 31, ADR-031) — the honesty
 * mechanism behind "Last Audit Results": a finding's status must be derived
 * from what actually changed in the policy document, never stored.
 */
class FreshnessEvaluatorTest {

    private static final PolicyRule RULE =
            new PolicyRule("r1", RuleEffect.ALLOW, "ADMIN", "service-a", "/api/**", "*", 0);

    private static AuditFinding finding(String action, List<String> ruleIds) {
        return new AuditFinding("f-1", "HIGH", "t", ruleIds, "rec", action, null, null, null);
    }

    @Test
    void untouchedRule_findingStaysCurrent() {
        Map<String, String> atRun = Map.of("r1", FreshnessEvaluator.hash(RULE));

        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("MODIFY_RULE", List.of("r1")), atRun, Map.of("r1", RULE), id -> false);

        assertThat(f).isEqualTo(FindingFreshness.CURRENT);
    }

    @Test
    void editedRule_findingIsRuleChanged() {
        Map<String, String> atRun = Map.of("r1", FreshnessEvaluator.hash(RULE));
        PolicyRule edited = new PolicyRule("r1", RuleEffect.ALLOW, "ADMIN", "service-a", "/api/narrower/**", "GET", 0);

        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("MODIFY_RULE", List.of("r1")), atRun, Map.of("r1", edited), id -> false);

        assertThat(f).isEqualTo(FindingFreshness.RULE_CHANGED);
    }

    @Test
    void removedRule_findingIsAddressed() {
        Map<String, String> atRun = Map.of("r1", FreshnessEvaluator.hash(RULE));

        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("MODIFY_RULE", List.of("r1")), atRun, Map.of(), id -> false);

        assertThat(f).isEqualTo(FindingFreshness.ADDRESSED);
    }

    @Test
    void disableSuggestion_doneViaToggle_findingIsAddressed() {
        Map<String, String> atRun = Map.of("r1", FreshnessEvaluator.hash(RULE));

        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("DISABLE_RULE", List.of("r1")), atRun, Map.of("r1", RULE), "r1"::equals);

        assertThat(f).isEqualTo(FindingFreshness.ADDRESSED);
    }

    @Test
    void disableSuggestion_notYetDone_findingStaysCurrent() {
        Map<String, String> atRun = Map.of("r1", FreshnessEvaluator.hash(RULE));

        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("DISABLE_RULE", List.of("r1")), atRun, Map.of("r1", RULE), id -> false);

        assertThat(f).isEqualTo(FindingFreshness.CURRENT);
    }

    @Test
    void findingWithNoRuleReferences_isAdviceAndStaysCurrent() {
        FindingFreshness f = FreshnessEvaluator.freshness(
                finding("ADD_RULE", List.of()), Map.of(), Map.of("r1", RULE), id -> false);

        assertThat(f).isEqualTo(FindingFreshness.CURRENT);
    }
}
