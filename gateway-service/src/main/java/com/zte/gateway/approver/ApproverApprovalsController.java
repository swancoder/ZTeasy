package com.zte.gateway.approver;

import com.zte.gateway.mcp.approval.ApprovalAlreadyDecidedException;
import com.zte.gateway.mcp.approval.ApprovalNotFoundException;
import com.zte.gateway.mcp.approval.ApprovalApiSupport;
import com.zte.gateway.mcp.approval.ApprovalEntitlement;
import com.zte.gateway.mcp.approval.ApprovalView;
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
    private final ApprovalEntitlement    entitlement;

    ApproverApprovalsController(PendingApprovalService approvalService, ApprovalEntitlement entitlement) {
        this.approvalService = approvalService;
        this.entitlement = entitlement;
    }

    @GetMapping
    public Mono<List<ApprovalView>> list(@AuthenticationPrincipal Jwt jwt) {
        ApprovalEntitlement.Decider decider = ApprovalApiSupport.decider(jwt);
        return approvalService.listPending().collectList()
                .map(pending -> ApprovalApiSupport.views(pending, entitlement, decider));
    }

    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<Object>> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApprovalApiSupport.respond(approvalService.approve(id, ApprovalApiSupport.decider(jwt)));
    }

    @PostMapping("/{id}/reject")
    public Mono<ResponseEntity<Object>> reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApprovalApiSupport.respond(approvalService.reject(id, ApprovalApiSupport.decider(jwt)));
    }

}
