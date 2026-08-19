package com.zte.gateway.admin;

import com.zte.gateway.mcp.approval.ApprovalAlreadyDecidedException;
import com.zte.gateway.mcp.approval.ApprovalNotFoundException;
import com.zte.gateway.mcp.approval.PendingApproval;
import com.zte.gateway.mcp.approval.PendingApprovalService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Console API (Stage 1, ADR-019): the human side of the 🟡 HOLD
 * outcome — list what's pending, approve, or reject.
 *
 * <p>Security: same {@code u2s-admin-console-api} YAML rule and {@link
 * AdminAuthorizationFilter} as every other {@code /api/v1/admin/**}
 * controller (that filter's path check is generic).
 */
@RestController
@RequestMapping("/api/v1/admin/approvals")
class AdminApprovalsController {

    private final PendingApprovalService approvalService;

    AdminApprovalsController(PendingApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public Mono<List<PendingApproval>> list() {
        return approvalService.listPending().collectList();
    }

    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<Object>> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return approvalService.approve(id, decidedBy(jwt))
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ApprovalNotFoundException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()))))
                .onErrorResume(ApprovalAlreadyDecidedException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()))));
    }

    @PostMapping("/{id}/reject")
    public Mono<ResponseEntity<Object>> reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return approvalService.reject(id, decidedBy(jwt))
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(ApprovalNotFoundException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()))))
                .onErrorResume(ApprovalAlreadyDecidedException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()))));
    }

    /** {@code preferred_username}, falling back to {@code sub} — same convention {@code McpProxyHandler} uses for agents. */
    private String decidedBy(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }
}
