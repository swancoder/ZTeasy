package com.zte.gateway.policy.def;

import java.util.List;

/**
 * Raised when a YAML policy file cannot be turned into a valid {@link PolicyDocument}
 * — unreadable file, malformed YAML, or schema-invalid content (see
 * {@link PolicyValidator}). Thrown from {@link PolicyDefinitionStore}'s constructor,
 * this fails Spring's {@code ApplicationContext} refresh at startup (fail-fast,
 * consistent with {@code SecurityConfig}'s deny-by-default posture) rather than
 * starting the gateway with an empty or stale rule set.
 */
public class PolicyLoadException extends RuntimeException {

    private final List<String> errors;

    public PolicyLoadException(String message) {
        this(message, List.of());
    }

    public PolicyLoadException(String message, List<String> errors) {
        super(message);
        this.errors = errors;
    }

    public PolicyLoadException(String message, Throwable cause) {
        super(message, cause);
        this.errors = List.of();
    }

    /** Individual validation failures, one per violation, empty for parse/IO failures. */
    public List<String> errors() {
        return errors;
    }
}
