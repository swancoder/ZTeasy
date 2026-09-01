package com.zte.gateway.policyaudit;

import com.zte.gateway.policy.def.PolicyRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pure freshness logic (Stage 31, ADR-031), package-visible and static so it
 * is unit-testable without a database or an LLM (SPECS §8's testing
 * convention).
 *
 * <p>Honesty rule: freshness is derived, never stored. The run captures a
 * content hash per referenced rule; at read time we compare against the live
 * document, so "this finding was addressed" can only ever be true because
 * the policy actually changed — not because someone clicked a status.
 */
final class FreshnessEvaluator {

    private FreshnessEvaluator() {}

    /** Canonical content hash of one rule — every field that changes behaviour. */
    static String hash(PolicyRule rule) {
        String canonical = String.join("|",
                rule.id(), String.valueOf(rule.effect()), rule.source(), rule.target(),
                String.valueOf(rule.pathPattern()), String.valueOf(rule.methods()),
                String.valueOf(rule.priority()), String.valueOf(rule.mcpTarget()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param finding        the stored finding
     * @param hashesAtRun    ruleId → hash captured when the audit ran
     * @param currentByRule  ruleId → rule in the LIVE document
     * @param isDisabled     whether a rule is currently switched off (activation overlay)
     */
    static FindingFreshness freshness(AuditFinding finding, Map<String, String> hashesAtRun,
                                       Map<String, PolicyRule> currentByRule, Predicate<String> isDisabled) {
        List<String> refs = finding.ruleIds() == null ? List.of() : finding.ruleIds();
        if (refs.isEmpty()) {
            // Nothing verifiable to compare against — the finding is advice,
            // and advice does not go stale mechanically.
            return FindingFreshness.CURRENT;
        }

        boolean allRemoved = refs.stream().noneMatch(currentByRule::containsKey);
        if (allRemoved) {
            return FindingFreshness.ADDRESSED;
        }

        if ("DISABLE_RULE".equals(finding.suggestedAction())
                && refs.stream().allMatch(id -> !currentByRule.containsKey(id) || isDisabled.test(id))) {
            return FindingFreshness.ADDRESSED;
        }

        boolean anyChanged = refs.stream().anyMatch(id -> {
            PolicyRule current = currentByRule.get(id);
            String then = hashesAtRun.get(id);
            return current != null && then != null && !then.equals(hash(current));
        });
        return anyChanged ? FindingFreshness.RULE_CHANGED : FindingFreshness.CURRENT;
    }
}
