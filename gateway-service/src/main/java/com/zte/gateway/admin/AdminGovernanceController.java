package com.zte.gateway.admin;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.governance.AgentActivitySummary;
import com.zte.gateway.governance.GovernanceReport;
import com.zte.gateway.governance.GovernanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Admin Console API (Stage 4, ADR-021): the governance dashboard's two
 * read-only views — per-agent activity and a live out-of-policy-attempts
 * feed — plus a combined export.
 *
 * <p>Security: same {@code u2s-admin-console-api} YAML rule and {@link
 * AdminAuthorizationFilter} as every other {@code /api/v1/admin/**}
 * controller (that filter's path check is generic).
 */
@RestController
@RequestMapping("/api/v1/admin/governance")
class AdminGovernanceController {

    private final GovernanceService governanceService;

    AdminGovernanceController(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping("/agent-activity")
    public Mono<List<AgentActivitySummary>> agentActivity(@RequestParam(defaultValue = "24") int hours) {
        return governanceService.agentActivity(hours);
    }

    @GetMapping("/out-of-policy")
    public Mono<List<RequestLog>> outOfPolicyAttempts() {
        return governanceService.outOfPolicyAttempts();
    }

    @GetMapping("/report")
    public Mono<GovernanceReport> report(@RequestParam(defaultValue = "24") int hours) {
        return governanceService.report(hours);
    }
}
