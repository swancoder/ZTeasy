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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
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

    public PendingApprovalService(PendingApprovalRepository repository, McpForwardService forwardService,
                                   McpAuditService auditService, McpSessionManager sessionManager,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.forwardService = forwardService;
        this.auditService = auditService;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    /** Persists a new held call — called from {@code McpProxyHandler.process()} on a HOLD decision. */
    public Mono<PendingApproval> hold(String sessionId, String agentId, String toolName, JsonRpcRequest rpc,
                                       String reason, String traceId, String clientIp, String userAgent,
                                       String displayIdentity) {
        PendingApproval approval = PendingApproval.requested(sessionId, agentId, toolName, writeJson(rpc.id()),
                writeJson(rpc.toolArguments()), null, reason, traceId, clientIp, userAgent, displayIdentity);
        return repository.save(approval);
    }

    /** Everything still awaiting a human decision, oldest first — the Admin Console's "Approvals" tab. */
    public Flux<PendingApproval> listPending() {
        return repository.findByStatusOrderByRequestedAtAsc(PendingApprovalStatus.PENDING.name());
    }

    public Mono<PendingApproval> approve(UUID id, String decidedBy) {
        return decide(id, true, decidedBy);
    }

    public Mono<PendingApproval> reject(UUID id, String decidedBy) {
        return decide(id, false, decidedBy);
    }

    private Mono<PendingApproval> decide(UUID id, boolean approve, String decidedBy) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ApprovalNotFoundException(id)))
                .flatMap(approval -> {
                    if (!PendingApprovalStatus.PENDING.name().equals(approval.status())) {
                        return Mono.error(new ApprovalAlreadyDecidedException(id, approval.status()));
                    }
                    return approve ? executeApproval(approval, decidedBy) : executeRejection(approval, decidedBy);
                });
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
