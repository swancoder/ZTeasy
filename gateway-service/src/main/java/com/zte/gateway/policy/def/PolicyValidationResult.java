package com.zte.gateway.policy.def;

import java.util.List;

/**
 * Outcome of {@link PolicyValidator#validate(PolicyDocument)}.
 *
 * <p>{@code errors} are load-blocking (schema violations: missing/blank required
 * fields, unknown schema version, duplicate rule ids, exact duplicate rules) —
 * any entry means the whole document is rejected (all-or-nothing, fail-closed).
 * {@code warnings} are non-blocking (a rule tuple matched by both an ALLOW and a
 * DENY rule) — the document still loads, since {@link PolicyMatcher}'s
 * deny-overrides-allow precedence resolves the conflict deterministically, but the
 * ambiguity is worth an operator's attention.
 */
public record PolicyValidationResult(List<String> errors, List<String> warnings) {

    public static PolicyValidationResult valid() {
        return new PolicyValidationResult(List.of(), List.of());
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
