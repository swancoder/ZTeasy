package com.zte.gateway.admin;

import com.zte.gateway.identity.IdentitySyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin Console API (ADR-014): manual IdP identity sync trigger, for the
 * "Sync Now" button in the React SPA's Identities tab.
 *
 * <p>Security: covered by the same {@code u2s-admin-console-api} YAML rule
 * and {@link AdminAuthorizationFilter} as {@link AdminPolicyController} —
 * that filter's path check is {@code /api/v1/admin/**} generically, so no
 * new security wiring is needed for this new sub-path.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminIdentitySyncController {

    private final IdentitySyncService identitySyncService;

    AdminIdentitySyncController(IdentitySyncService identitySyncService) {
        this.identitySyncService = identitySyncService;
    }

    @PostMapping("/identities/sync")
    public Mono<ResponseEntity<Map<String, Object>>> sync() {
        return identitySyncService.syncNow()
                .map(count -> ResponseEntity.ok(Map.<String, Object>of("synced", count)))
                .onErrorResume(ex -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage()))));
    }
}
