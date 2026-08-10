package com.zte.gateway.admin;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Admin Console API (ADR-013): exposes the latest request audit trail to the
 * authenticated human operator, via the React SPA served at {@code /admin/}.
 *
 * <p>Security: covered by the same {@code u2s-admin-console-api} YAML rule
 * and {@link AdminAuthorizationFilter} as {@link AdminPolicyController} — that
 * filter's path check is {@code /api/v1/admin/**} generically, so no new
 * security wiring is needed for this new sub-path.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminAuditLogController {

    private final RequestLogRepository repository;

    AdminAuditLogController(RequestLogRepository repository) {
        this.repository = repository;
    }

    /** Latest 100 request log rows, newest first — capped at the query level (see {@link RequestLogRepository}). */
    @GetMapping("/audit-logs")
    public Flux<RequestLog> latestAuditLogs() {
        return repository.findTop100ByOrderByTimestampDesc();
    }
}
