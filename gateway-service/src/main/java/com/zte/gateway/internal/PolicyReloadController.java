package com.zte.gateway.internal;

import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyReloadResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Runtime, no-downtime reload of the YAML policy definitions (ADR-011).
 *
 * <p>Security: same posture as {@link InternalPolicyController} — served under
 * {@code /api/v1/internal/**}, permitAll via {@link InternalSecurityConfig},
 * protected at the network layer rather than by JWT (see that class's Javadoc
 * for the production-upgrade note).
 *
 * <p>Re-reads and re-validates the configured YAML file off the Netty event
 * loop and atomically swaps {@link PolicyDefinitionStore}'s active document on
 * success. In-flight requests that already read the previous document via
 * {@code PolicyDefinitionStore.current()} complete against it unaffected. On
 * validation failure, the previous document remains active and the response
 * reports the validation errors — never a partial application.
 */
@RestController
@RequestMapping("/api/v1/internal/policies")
class PolicyReloadController {

    private final PolicyDefinitionStore policyDefinitionStore;

    PolicyReloadController(PolicyDefinitionStore policyDefinitionStore) {
        this.policyDefinitionStore = policyDefinitionStore;
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reload() {
        return policyDefinitionStore.reload().map(this::toResponse);
    }

    private ResponseEntity<Map<String, Object>> toResponse(PolicyReloadResult result) {
        if (result.success()) {
            return ResponseEntity.ok(Map.of("status", "success", "timestamp", result.timestamp().toString()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "failed",
                "errors", result.errors(),
                "timestamp", result.timestamp().toString()));
    }
}
