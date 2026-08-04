package com.zte.gateway.policy.def;

/**
 * Effect of a {@link PolicyRule} or a resolved {@link PolicyEvaluation}.
 *
 * <p>Bound directly from YAML ({@code effect: ALLOW|DENY}) and from the
 * {@code zte.policy.default-effect} property — Spring's relaxed enum binding
 * rejects any other value at startup, giving fail-fast validation for free.
 */
public enum RuleEffect {
    ALLOW,
    DENY
}
