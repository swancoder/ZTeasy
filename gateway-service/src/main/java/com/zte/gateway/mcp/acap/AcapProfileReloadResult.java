package com.zte.gateway.mcp.acap;

import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

/**
 * Outcome of an {@link AcapProfileStore#reload()} call (Stage 3, ADR-020).
 *
 * <p>Always {@code 200} — unlike {@code PolicyReloadResult}, there's no
 * failure state to report: a malformed individual profile file is logged
 * and skipped by {@link AcapProfileFileLoader}, not surfaced as a reload
 * failure (see that class's Javadoc for why).
 */
public record AcapProfileReloadResult(int loadedCount, Instant timestamp) {

    public static AcapProfileReloadResult of(int loadedCount) {
        return new AcapProfileReloadResult(loadedCount, Instant.now());
    }

    public ResponseEntity<Map<String, Object>> toResponseEntity() {
        return ResponseEntity.ok(Map.of(
                "status", "success", "loadedCount", loadedCount, "timestamp", timestamp.toString()));
    }
}
