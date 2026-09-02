package com.zte.gateway.admin;

import com.zte.gateway.mcp.approval.ApprovalAlreadyDecidedException;
import com.zte.gateway.mcp.approval.ApprovalNotFoundException;
import com.zte.gateway.mcp.approval.ApprovalApiSupport;
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
    public Mono<List<ApprovalView>> list(@AuthenticationPrincipal Jwt jwt) {
        return approvalService.listPendingFor(ApprovalApiSupport.decider(jwt));
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
