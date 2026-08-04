package com.zte.gateway.policy.def;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a parsed {@link PolicyDocument} before it is allowed to become the
 * active policy set.
 *
 * <p>Pure in-memory checks, no I/O — safe to call from any thread. Collects every
 * violation in one pass (not fail-fast on the first) so an operator sees the full
 * list of what to fix, each entry naming the offending category/rule id.
 */
@Component
public class PolicyValidator {

    private static final Set<String> VALID_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    public PolicyValidationResult validate(PolicyDocument document) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (document == null) {
            return new PolicyValidationResult(List.of("policy document is null"), List.of());
        }
        if (document.schemaVersion() == null
                || document.schemaVersion() != PolicyDocument.SUPPORTED_SCHEMA_VERSION) {
            errors.add("unsupported schemaVersion: " + document.schemaVersion()
                    + " (expected " + PolicyDocument.SUPPORTED_SCHEMA_VERSION + ")");
        }

        validateCategory("users2service", document.users2service(), true, errors, warnings);
        validateCategory("service2service", document.service2service(), true, errors, warnings);
        validateCategory("agentMcpToolCalls", document.agentMcpToolCalls(), false, errors, warnings);

        checkDuplicateIds(document, errors);

        return new PolicyValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateCategory(String category, List<PolicyRule> rules, boolean pathAware,
                                   List<String> errors, List<String> warnings) {
        Map<String, PolicyRule> seenByTuple = new HashMap<>();

        for (int i = 0; i < rules.size(); i++) {
            PolicyRule rule = rules.get(i);
            String ref = category + "[" + i + "]" + (rule.id() != null ? " id=" + rule.id() : "");

            if (isBlank(rule.id())) errors.add(ref + ": missing required field 'id'");
            if (rule.effect() == null) errors.add(ref + ": missing required field 'effect'");
            if (isBlank(rule.source())) errors.add(ref + ": missing required field 'source'");
            if (isBlank(rule.target())) errors.add(ref + ": missing required field 'target'");
            if (pathAware && rule.methods() != null && !isValidMethods(rule.methods())) {
                errors.add(ref + ": invalid 'methods' value '" + rule.methods()
                        + "' (expected '*' or a comma-separated list of " + VALID_METHODS + ")");
            }

            String tuple = rule.source() + "|" + rule.target() + "|" + rule.pathPattern() + "|" + rule.methods();
            PolicyRule prior = seenByTuple.putIfAbsent(tuple, rule);
            if (prior != null) {
                if (prior.effect() == rule.effect()) {
                    errors.add(ref + ": duplicate rule (same source/target/path/methods/effect as '"
                            + prior.id() + "')");
                } else {
                    warnings.add(category + ": conflicting ALLOW/DENY rules for the same source/target/path/methods ('"
                            + prior.id() + "' vs '" + rule.id()
                            + "') — DENY takes precedence at evaluation time regardless of declaration order");
                }
            }
        }
    }

    private void checkDuplicateIds(PolicyDocument document, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (PolicyRule rule : allRules(document)) {
            if (isBlank(rule.id())) continue; // already reported by validateCategory
            if (!seen.add(rule.id())) {
                errors.add("duplicate rule id across document: '" + rule.id() + "'");
            }
        }
    }

    private List<PolicyRule> allRules(PolicyDocument document) {
        List<PolicyRule> all = new ArrayList<>();
        all.addAll(document.users2service());
        all.addAll(document.service2service());
        all.addAll(document.agentMcpToolCalls());
        return all;
    }

    private boolean isValidMethods(String methods) {
        if ("*".equals(methods)) return true;
        for (String m : methods.split(",")) {
            if (!VALID_METHODS.contains(m.trim().toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
