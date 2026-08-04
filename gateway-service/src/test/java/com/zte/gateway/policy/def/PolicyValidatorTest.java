package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PolicyValidator}.
 */
class PolicyValidatorTest {

    private final PolicyValidator validator = new PolicyValidator();

    private PolicyRule rule(String id, RuleEffect effect, String source, String target) {
        return new PolicyRule(id, effect, source, target, null, null, 0);
    }

    @Test
    void validDocument_passesWithNoErrorsOrWarnings() {
        PolicyDocument doc = new PolicyDocument(1,
                List.of(rule("u1", RuleEffect.ALLOW, "ADMIN", "service-a")),
                List.of(),
                List.of(rule("m1", RuleEffect.DENY, "*", "delete*")));

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void unsupportedSchemaVersion_isRejected() {
        PolicyDocument doc = new PolicyDocument(99, List.of(), List.of(), List.of());

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("schemaVersion"));
    }

    @Test
    void missingRequiredFields_areAllCollectedInOnePass() {
        PolicyRule blank = new PolicyRule(null, null, "", "", null, null, 0);
        PolicyDocument doc = new PolicyDocument(1, List.of(blank), List.of(), List.of());

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.errors())
                .anyMatch(e -> e.contains("'id'"))
                .anyMatch(e -> e.contains("'effect'"))
                .anyMatch(e -> e.contains("'source'"))
                .anyMatch(e -> e.contains("'target'"));
    }

    @Test
    void duplicateRuleId_acrossCategories_isRejected() {
        PolicyRule a = rule("dup", RuleEffect.ALLOW, "ADMIN", "service-a");
        PolicyRule b = rule("dup", RuleEffect.DENY, "agent-a", "get_deals");
        PolicyDocument doc = new PolicyDocument(1, List.of(a), List.of(), List.of(b));

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("duplicate rule id"));
    }

    @Test
    void exactDuplicateRule_sameTupleAndEffect_isRejected() {
        PolicyRule a = rule("a1", RuleEffect.ALLOW, "ADMIN", "service-a");
        PolicyRule b = rule("a2", RuleEffect.ALLOW, "ADMIN", "service-a");
        PolicyDocument doc = new PolicyDocument(1, List.of(a, b), List.of(), List.of());

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("duplicate rule"));
    }

    @Test
    void conflictingAllowAndDeny_sameTuple_isWarningNotError() {
        PolicyRule allow = rule("a1", RuleEffect.ALLOW, "ADMIN", "service-a");
        PolicyRule deny  = rule("d1", RuleEffect.DENY, "ADMIN", "service-a");
        PolicyDocument doc = new PolicyDocument(1, List.of(allow, deny), List.of(), List.of());

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("conflicting"));
    }

    @Test
    void invalidMethodsValue_isRejected() {
        PolicyRule invalid = new PolicyRule("r1", RuleEffect.ALLOW, "ADMIN", "service-a", "/**", "FETCH", 0);
        PolicyDocument doc = new PolicyDocument(1, List.of(invalid), List.of(), List.of());

        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("methods"));
    }
}
