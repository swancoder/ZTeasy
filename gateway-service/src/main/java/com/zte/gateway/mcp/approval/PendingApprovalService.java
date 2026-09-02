package com.zte.gateway.mcp.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.mcp.McpForwardService;
import com.zte.gateway.mcp.McpSessionManager;
import com.zte.gateway.mcp.audit.McpAuditEvent;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business layer for the 🟡 HOLD outcome (Stage 1, ADR-019): persists a held
 * tool call, lists what's awaiting review, and — on
 * {@code POST /api/v1/admin/approvals/{id}/approve} or {@code /reject} —
 * executes the human's decision.
 *
 * <p>Approve reconstructs and forwards the exact originally-requested call via
 * {@link McpForwardService} (the same path {@code McpProxyHandler}'s ALLOW
 * branch uses), audits it, and — if the originating SSE session is still
 * open — emits the result into it. Reject audits the refusal and, likewise
 * if the session is still open, emits an honest denial. Either way the
 * decision is durable and audited even if the agent's session has since
 * closed; only the "push the result back into that live connection" part is
 * best-effort (see {@link PendingApproval}'s Javadoc).
 */
@Service
public class PendingApprovalService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-MCP-APPROVALS");
    private static final String PROCESS_ID = String.valueOf(ProcessHandle.current().pid());

    private final PendingApprovalRepository repository;
    private final McpForwardService forwardService;
    private final McpAuditService auditService;
    private final McpSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private final ApprovalEntitlement entitlement;
    private final ApprovalAudience audience;
    private final ApprovalNotifier notifier;
    private final ApprovalNotificationRepository notifications;
    private final Duration ttl;

    public PendingApprovalService(PendingApprovalRepository repository, McpForwardService forwardService,
                                   McpAuditService auditService, McpSessionManager sessionManager,
                                   ObjectMapper objectMapper, ApprovalEntitlement entitlement,
                                   ApprovalAudience audience, ApprovalNotifier notifier,
                                   ApprovalNotificationRepository notifications,
                                   @Value("${zte.approvals.ttl-minutes:1440}") long ttlMinutes) {
        this.audience = audience;
        this.notifier = notifier;
        this.notifications = notifications;
        this.repository = repository;
        this.forwardService = forwardService;
        this.auditService = auditService;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.entitlement = entitlement;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** Persists a new held call — called from {@code McpProxyHandler.process()} on a HOLD decision. */
    public Mono<PendingApproval> hold(String sessionId, String agentId, String toolName, JsonRpcRequest rpc,
                                       String reason, String routeTo, String traceId, String clientIp,
                                       String userAgent, String displayIdentity) {
        PendingApproval approval = PendingApproval.requested(sessionId, agentId, toolName, writeJson(rpc.id()),
                writeJson(rpc.toolArguments()), routeTo, reason, traceId, clientIp, userAgent, displayIdentity, ttl);
        // Notify after the row is durable, never before: a message pointing at an
        // approval that failed to persist would be worse than no message.
        return repository.save(approval).doOnNext(notifier::notifyRaised);
    }

    /** Everything still awaiting a human decision, oldest first — the Admin Console's "Approvals" tab. */
    public Flux<PendingApproval> listPending() {
        return repository.findByStatusOrderByRequestedAtAsc(PendingApprovalStatus.PENDING.name());
    }

    /**
     * The queue as one viewer sees it (ADR-034/ADR-035) — entitlement, countdown,
     * addressee and delivery status resolved together, so both approval surfaces
     * cannot disagree about any of them. Deliveries are fetched in one query for
     * the whole page rather than one per row.
     */
    public Mono<List<ApprovalView>> listPendingFor(ApprovalEntitlement.Decider decider) {
        return listPending().collectList().flatMap(pending -> {
            if (pending.isEmpty()) {
                return Mono.just(List.<ApprovalView>of());
            }
            List<UUID> ids = pending.stream().map(PendingApproval::id).toList();
            return notifications.findByApprovalIdInOrderByCreatedAtDesc(ids)
                    .collectList()
                    .map(rows -> {
                        Map<UUID, ApprovalNotification> latest = new HashMap<>();
                        for (ApprovalNotification n : rows) {
                            latest.putIfAbsent(n.approvalId(), n);   // ordered newest-first
                        }
                        Instant now = Instant.now();
                        return pending.stream()
                                .map(a -> ApprovalView.of(a, entitlement, audience, decider, latest.get(a.id()), now))
                                .toList();
                    });
        });
    }

    public Mono<PendingApproval> approve(UUID id, ApprovalEntitlement.Decider decider) {
        return decide(id, true, decider);
    }

    public Mono<PendingApproval> reject(UUID id, ApprovalEntitlement.Decider decider) {
        return decide(id, false, decider);
    }

    private Mono<PendingApproval> decide(UUID id, boolean approve, ApprovalEntitlement.Decider decider) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ApprovalNotFoundException(id)))
                .flatMap(approval -> {
                    if (!PendingApprovalStatus.PENDING.name().equals(approval.status())) {
                        return Mono.error(new ApprovalAlreadyDecidedException(id, approval.status()));
                    }
                    // Checked here rather than only in the sweeper: a row can be past
                    // its deadline for up to one sweep interval and must not execute
                    // in that window just because a timer hasn't fired yet (ADR-034).
                    if (approval.isExpired(Instant.now())) {
                        return expire(approval).then(Mono.error(new ApprovalExpiredException(id, approval.expiresAt())));
                    }
                    return entitlement.refusalReason(approval, decider)
                            .<Mono<PendingApproval>>map(reason -> Mono.error(new ApprovalNotRoutedToYouException(reason)))
                            .orElseGet(() -> approve
                                    ? executeApproval(approval, decider.username())
                                    : executeRejection(approval, decider.username()));
                });
    }

    /**
     * Terminal state for an approval nobody decided in time. Writes the same kind of
     * audit row a human decision would, because "expired" is an outcome of the
     * governance process, not an absence of one.
     */
    Mono<PendingApproval> expire(PendingApproval approval) {
        log.info("MCP EXPIRE approvalId={} agentId={} tool={} expiresAt={}",
                approval.id(), approval.agentId(), approval.toolName(), approval.expiresAt());
        auditService.record(new McpAuditEvent(PROCESS_ID, approval.agentId(), approval.toolName(), "EXPIRED",
                Instant.now(), approval.sessionId(), "Expired undecided at " + approval.expiresAt(),
                approval.traceId(), approval.clientIp(), approval.userAgent(), approval.displayIdentity(),
                approval.argumentsJson()));
        emitIfSessionOpen(approval.sessionId(), JsonRpcResponse.denied(
                readJson(approval.rpcIdJson(), Object.class), "Expired without a human decision"));
        return repository.save(approval.decided(PendingApprovalStatus.EXPIRED, "system"));
    }

    /** Everything past its deadline and still pending — the sweeper's input. */
    public Flux<PendingApproval> findExpired(Instant now) {
        return repository.findByStatusAndExpiresAtBefore(PendingApprovalStatus.PENDING.name(), now);
    }

    private Mono<PendingApproval> executeApproval(PendingApproval approval, String decidedBy) {
        JsonRpcRequest rpc = reconstruct(approval);
        return forwardService.execute(approval.agentId(), rpc)
                .doOnNext(resp -> {
                    log.info("MCP APPROVE approvalId={} agentId={} tool={} decidedBy={}",
                            approval.id(), approval.agentId(), approval.toolName(), decidedBy);
                    auditService.record(new McpAuditEvent(PROCESS_ID, approval.agentId(), approval.toolName(),
                            "APPROVED", Instant.now(), approval.sessionId(), "Approved by " + decidedBy,
                            approval.traceId(), approval.clientIp(), approval.userAgent(),
                            approval.displayIdentity(), approval.argumentsJson()));
                    emitIfSessionOpen(approval.sessionId(), resp);
                })
                .then(repository.save(approval.decided(PendingApprovalStatus.APPROVED, decidedBy)));
    }

    private Mono<PendingApproval> executeRejection(PendingApproval approval, String decidedBy) {
        log.info("MCP REJECT approvalId={} agentId={} tool={} decidedBy={}",
                approval.id(), approval.agentId(), approval.toolName(), decidedBy);
        auditService.record(new McpAuditEvent(PROCESS_ID, approval.agentId(), approval.toolName(), "REJECTED",
                Instant.now(), approval.sessionId(), "Rejected by " + decidedBy, approval.traceId(),
                approval.clientIp(), approval.userAgent(), approval.displayIdentity(), approval.argumentsJson()));
        emitIfSessionOpen(approval.sessionId(),
                JsonRpcResponse.denied(readJson(approval.rpcIdJson(), Object.class), "Rejected by " + decidedBy));
        return repository.save(approval.decided(PendingApprovalStatus.REJECTED, decidedBy));
    }

    private JsonRpcRequest reconstruct(PendingApproval approval) {
        Object id = readJson(approval.rpcIdJson(), Object.class);
        Map<String, Object> arguments = readJson(approval.argumentsJson(), new TypeReference<Map<String, Object>>() {});
        return new JsonRpcRequest("2.0", id, "tools/call", Map.of("name", approval.toolName(), "arguments", arguments));
    }

    private void emitIfSessionOpen(String sessionId, JsonRpcResponse response) {
        if (!sessionManager.exists(sessionId)) {
            log.info("Approval decided but MCP session {} has since closed — decision still recorded/audited, "
                    + "result cannot be pushed back to the agent (see ADR-019)", sessionId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            sessionManager.emit(sessionId, ServerSentEvent.<String>builder().event("message").data(json).build());
        } catch (Exception ex) {
            log.warn("Failed to emit approval decision into MCP session {}", sessionId, ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Corrupt stored JSON for pending approval: " + json, ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Corrupt stored JSON for pending approval: " + json, ex);
        }
    }
}
