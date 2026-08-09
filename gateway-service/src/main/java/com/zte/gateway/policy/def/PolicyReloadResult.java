package com.zte.gateway.policy.def;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    /**
     * Renders this result as the JSON body shared by every reload endpoint
     * ({@code /api/v1/internal/policies/reload} and
     * {@code /api/v1/admin/policies/reload}) — one implementation, so both
     * render identically.
     */
    public ResponseEntity<Map<String, Object>> toResponseEntity() {
        if (success) {
            return ResponseEntity.ok(Map.of("status", "success", "timestamp", timestamp.toString()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "failed",
                "errors", errors,
                "timestamp", timestamp.toString()));
    }
}
