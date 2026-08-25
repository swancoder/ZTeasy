package com.zte.gateway.approver;

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
 * The standalone Approval Center's API (ADR-026): the exact same 🟡 HOLD
 * queue operations {@code AdminApprovalsController} exposes under
 * {@code /api/v1/admin/approvals} (ADR-019), re-exposed under
 * {@code /api/v1/approver/**} with a broader audience — any authenticated
 * interactive user ({@code USER} or {@code ADMIN} realm role), enforced by
 * the {@code u2s-approver-api-*} YAML rules via
 * {@link com.zte.gateway.admin.AdminAuthorizationFilter} (whose path check
 * covers this prefix too).
 *
 * <p>Deliberately role-scoped rather than a bare {@code source: "*"} rule:
 * an agent's own client-credentials JWT carries no realm role, so an agent
 * can never call this API to approve its own held call — only a human can.
 *
 * <p>Delegates to the same {@link PendingApprovalService} as the admin
 * controller — one decision path, one audit trail ({@code APPROVED}/
 * {@code REJECTED} rows), regardless of which surface the human used.
 */
@RestController
@RequestMapping("/api/v1/approver/approvals")
class ApproverApprovalsController {

    private final PendingApprovalService approvalService;

    ApproverApprovalsController(PendingApprovalService approvalService) {
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

    /** {@code preferred_username}, falling back to {@code sub} — same convention as {@code AdminApprovalsController}. */
    private String decidedBy(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }
}
