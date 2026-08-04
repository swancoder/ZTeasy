package com.zte.gateway.policy.def;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a {@link PolicyDefinitionStore#reload()} call.
 */
public record PolicyReloadResult(boolean success, List<String> errors, Instant timestamp) {

    public static PolicyReloadResult ok() {
        return new PolicyReloadResult(true, List.of(), Instant.now());
    }

    public static PolicyReloadResult failure(List<String> errors) {
        return new PolicyReloadResult(false, errors, Instant.now());
    }
}
