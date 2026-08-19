package com.zte.gateway.mcp.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.mcp.McpForwardService;
import com.zte.gateway.mcp.McpSessionManager;
import com.zte.gateway.mcp.audit.McpAuditEvent;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PendingApprovalService} (Stage 1, ADR-019). */
@ExtendWith(MockitoExtension.class)
class PendingApprovalServiceTest {

    @Mock PendingApprovalRepository repository;
    @Mock McpForwardService forwardService;
    @Mock McpAuditService auditService;
    @Mock McpSessionManager sessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PendingApprovalService newService() {
        return new PendingApprovalService(repository, forwardService, auditService, sessionManager, objectMapper);
    }

    private PendingApproval pending(UUID id) {
        return new PendingApproval(id, "session-1", "crm-account-health-emea-01", "send_email", "7",
                "{\"to\":\"rep@nordwind.example\"}", null, "held for human approval", "PENDING",
                Instant.now(), null, null, "trace-1", "203.0.113.10", "agent/1.0", "crm-account-health-emea-01");
    }

    @Test
    void hold_persistsPendingRow() {
        PendingApprovalService service = newService();
        JsonRpcRequest rpc = new JsonRpcRequest("2.0", 7, "tools/call",
                Map.of("name", "send_email", "arguments", Map.of("to", "rep@nordwind.example")));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.hold("session-1", "crm-account-health-emea-01", "send_email", rpc,
                        "held for human approval", "trace-1", "203.0.113.10", "agent/1.0",
                        "crm-account-health-emea-01"))
                .assertNext(approval -> {
                    assertThat(approval.status()).isEqualTo("PENDING");
                    assertThat(approval.toolName()).isEqualTo("send_email");
                    assertThat(approval.argumentsJson()).contains("rep@nordwind.example");
                })
                .verifyComplete();
    }

    @Test
    void approve_unknownId_errors() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(id, "admin@zte"))
                .expectError(ApprovalNotFoundException.class)
                .verify();
    }

    @Test
    void approve_alreadyDecided_errors() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.just(pending(id).decided(PendingApprovalStatus.REJECTED, "someone-else")));

        StepVerifier.create(service.approve(id, "admin@zte"))
                .expectError(ApprovalAlreadyDecidedException.class)
                .verify();
    }

    @Test
    void approve_pending_forwardsReconstructedCall_audits_emitsIntoOpenSession_marksApproved() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        PendingApproval approval = pending(id);
        JsonRpcResponse backendResponse = JsonRpcResponse.success(7, Map.of("status", "sent"));

        when(repository.findById(id)).thenReturn(Mono.just(approval));
        when(forwardService.execute(any())).thenReturn(Mono.just(backendResponse));
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, "admin@zte"))
                .assertNext(saved -> {
                    assertThat(saved.status()).isEqualTo("APPROVED");
                    assertThat(saved.decidedBy()).isEqualTo("admin@zte");
                })
                .verifyComplete();

        ArgumentCaptor<JsonRpcRequest> rpcCaptor = ArgumentCaptor.forClass(JsonRpcRequest.class);
        verify(forwardService).execute(rpcCaptor.capture());
        assertThat(rpcCaptor.getValue().toolName()).isEqualTo("send_email");
        assertThat(rpcCaptor.getValue().toolArguments()).containsEntry("to", "rep@nordwind.example");

        ArgumentCaptor<McpAuditEvent> auditCaptor = ArgumentCaptor.forClass(McpAuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().status()).isEqualTo("APPROVED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> sseCaptor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getValue().data()).contains("sent");
    }

    @Test
    void approve_sessionSinceClosed_stillDecides_justDoesNotEmit() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        PendingApproval approval = pending(id);

        when(repository.findById(id)).thenReturn(Mono.just(approval));
        when(forwardService.execute(any())).thenReturn(Mono.just(JsonRpcResponse.success(7, Map.of())));
        when(sessionManager.exists("session-1")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, "admin@zte"))
                .assertNext(saved -> assertThat(saved.status()).isEqualTo("APPROVED"))
                .verifyComplete();

        verify(sessionManager, never()).emit(any(), any());
    }

    @Test
    void reject_pending_audits_emitsDenialIntoOpenSession_marksRejected() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        PendingApproval approval = pending(id);

        when(repository.findById(id)).thenReturn(Mono.just(approval));
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.reject(id, "admin@zte"))
                .assertNext(saved -> assertThat(saved.status()).isEqualTo("REJECTED"))
                .verifyComplete();

        verify(forwardService, never()).execute(any());

        ArgumentCaptor<McpAuditEvent> auditCaptor = ArgumentCaptor.forClass(McpAuditEvent.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().status()).isEqualTo("REJECTED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> sseCaptor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), sseCaptor.capture());
        assertThat(sseCaptor.getValue().data()).contains("isError");
    }

    @Test
    void listPending_delegatesToRepositoryFilteredByStatus() {
        PendingApprovalService service = newService();
        when(repository.findByStatusOrderByRequestedAtAsc("PENDING"))
                .thenReturn(reactor.core.publisher.Flux.just(pending(UUID.randomUUID())));

        StepVerifier.create(service.listPending())
                .expectNextCount(1)
                .verifyComplete();
    }
}
