package com.zte.gateway.mcp.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * The parts the Admin Console's approvals tab and the standalone Approval Center
 * (ADR-026) must do identically: read the decider out of a token, and turn a
 * refusal into the right status code. Rendering the queue for that decider lives
 * in {@code PendingApprovalService.listPendingFor}, which also needs the delivery
 * rows (ADR-035).
 *
 * <p>Shared deliberately — two surfaces onto one queue that disagreed about who may
 * decide what would be a governance bug, not a UI inconsistency.
 */
public final class ApprovalApiSupport {

    private ApprovalApiSupport() {}

    /** {@code preferred_username} (falling back to {@code sub}) plus {@code realm_access.roles}. */
    @SuppressWarnings("unchecked")
    public static ApprovalEntitlement.Decider decider(Jwt jwt) {
        if (jwt == null) {
            return ApprovalEntitlement.Decider.of("unknown", List.of());
        }
        String username = jwt.getClaimAsString("preferred_username");
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        List<String> roles = realmAccess == null ? List.of()
                : (List<String>) realmAccess.getOrDefault("roles", List.of());
        return ApprovalEntitlement.Decider.of(username != null ? username : jwt.getSubject(), roles);
    }

    /**
     * One error contract for both surfaces: 404 unknown, 409 already decided or
     * expired (the state moved on), 403 routed to someone else (the caller is the
     * problem, not the state).
     */
    public static Mono<ResponseEntity<Object>> respond(Mono<PendingApproval> decision) {
        return decision
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ApprovalNotFoundException.class, ex -> status(HttpStatus.NOT_FOUND, ex))
                .onErrorResume(ApprovalAlreadyDecidedException.class, ex -> status(HttpStatus.CONFLICT, ex))
                .onErrorResume(ApprovalExpiredException.class, ex -> status(HttpStatus.CONFLICT, ex))
                .onErrorResume(ApprovalNotRoutedToYouException.class, ex -> status(HttpStatus.FORBIDDEN, ex));
    }

    private static Mono<ResponseEntity<Object>> status(HttpStatus code, RuntimeException ex) {
        return Mono.just(ResponseEntity.status(code).body(Map.of("error", ex.getMessage())));
    }
}
