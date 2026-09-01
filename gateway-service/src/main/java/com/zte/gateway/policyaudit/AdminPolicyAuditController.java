package com.zte.gateway.policyaudit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin API for AI policy audits (Stage 31, ADR-031) — same
 * {@code /api/v1/admin/**} posture as every other console endpoint
 * ({@code AdminAuthorizationFilter} + the {@code u2s-admin-console-api}
 * rule): running an audit is an administrative act.
 */
@RestController
@RequestMapping("/api/v1/admin/policy-audit")
class AdminPolicyAuditController {

    private final PolicyAuditService auditService;

    AdminPolicyAuditController(PolicyAuditService auditService) {
        this.auditService = auditService;
    }

    /** Long call by design (an LLM reviews the document) — the UI shows progress. */
    @PostMapping("/run")
    public Mono<ResponseEntity<Object>> run(@AuthenticationPrincipal Jwt jwt) {
        return auditService.run(username(jwt))
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Audit failed: " + e.getMessage()))));
    }

    @GetMapping("/latest")
    public Mono<ResponseEntity<Object>> latest() {
        return auditService.latest()
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No audit has been run yet")));
    }

    @PostMapping("/latest/findings/{findingId}/acknowledge")
    public Mono<ResponseEntity<Object>> acknowledge(@PathVariable String findingId,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return auditService.acknowledge(findingId, username(jwt))
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No audit has been run yet")));
    }

    private String username(Jwt jwt) {
        if (jwt == null) return "unknown";
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null ? preferred : jwt.getSubject();
    }
}
