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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        return new PendingApprovalService(repository, forwardService, auditService, sessionManager, objectMapper,
                new ApprovalEntitlement(), 1440);
    }

    private PendingApproval pending(UUID id) {
        return new PendingApproval(id, "session-1", "crm-account-health-emea-01", "send_email", "7",
                "{\"to\":\"rep@nordwind.example\"}", null, "held for human approval", "PENDING",
                Instant.now(), Instant.now().plus(Duration.ofHours(24)), null, null, "trace-1", "203.0.113.10",
                "agent/1.0", "crm-account-health-emea-01");
    }

    @Test
    void hold_persistsPendingRow() {
        PendingApprovalService service = newService();
        JsonRpcRequest rpc = new JsonRpcRequest("2.0", 7, "tools/call",
                Map.of("name", "send_email", "arguments", Map.of("to", "rep@nordwind.example")));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.hold("session-1", "crm-account-health-emea-01", "send_email", rpc,
                        "held for human approval", null, "trace-1", "203.0.113.10", "agent/1.0",
                        "crm-account-health-emea-01"))
                .assertNext(approval -> {
                    assertThat(approval.status()).isEqualTo("PENDING");
                    assertThat(approval.toolName()).isEqualTo("send_email");
                    assertThat(approval.argumentsJson()).contains("rep@nordwind.example");
                })
                .verifyComplete();
    }

    /**
     * ADR-034: the deadline is enforced where the decision happens, not only by the
     * sweeper — otherwise a call could execute during the window between expiry and
     * the next sweep, which is exactly the window a slow reviewer sits in.
     */
    @Test
    void approve_pastDeadline_expiresInsteadOfExecuting() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        PendingApproval overdue = new PendingApproval(id, "session-1", "crm-account-health-emea-01", "send_email",
                "7", "{}", null, "held", "PENDING", Instant.now().minus(Duration.ofHours(25)),
                Instant.now().minus(Duration.ofHours(1)), null, null, "trace-1", null, null, null);
        when(repository.findById(id)).thenReturn(Mono.just(overdue));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("zte-admin", java.util.List.of("ADMIN"))))
                .expectError(ApprovalExpiredException.class)
                .verify();

        verify(forwardService, never()).execute(anyString(), any());
        ArgumentCaptor<PendingApproval> saved = ArgumentCaptor.forClass(PendingApproval.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("EXPIRED");
    }

    /** A routed approval refuses the wrong human before anything reaches the backend. */
    @Test
    void approve_routedElsewhere_refusesAndNeverForwards() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        PendingApproval routed = new PendingApproval(id, "session-1", "crm-account-health-emea-01", "send_email",
                "7", "{}", "role:APPROVER", "held", "PENDING", Instant.now(),
                Instant.now().plus(Duration.ofHours(24)), null, null, "trace-1", null, null, null);
        when(repository.findById(id)).thenReturn(Mono.just(routed));

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("zte-test-user", java.util.List.of("USER"))))
                .expectError(ApprovalNotRoutedToYouException.class)
                .verify();

        verify(forwardService, never()).execute(anyString(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void approve_unknownId_errors() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("admin@zte", java.util.List.of("ADMIN"))))
                .expectError(ApprovalNotFoundException.class)
                .verify();
    }

    @Test
    void approve_alreadyDecided_errors() {
        PendingApprovalService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.just(pending(id).decided(PendingApprovalStatus.REJECTED, "someone-else")));

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("admin@zte", java.util.List.of("ADMIN"))))
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
        when(forwardService.execute(anyString(), any())).thenReturn(Mono.just(backendResponse));
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("admin@zte", java.util.List.of("ADMIN"))))
                .assertNext(saved -> {
                    assertThat(saved.status()).isEqualTo("APPROVED");
                    assertThat(saved.decidedBy()).isEqualTo("admin@zte");
                })
                .verifyComplete();

        ArgumentCaptor<JsonRpcRequest> rpcCaptor = ArgumentCaptor.forClass(JsonRpcRequest.class);
        verify(forwardService).execute(anyString(), rpcCaptor.capture());
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
        when(forwardService.execute(anyString(), any())).thenReturn(Mono.just(JsonRpcResponse.success(7, Map.of())));
        when(sessionManager.exists("session-1")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, ApprovalEntitlement.Decider.of("admin@zte", java.util.List.of("ADMIN"))))
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

        StepVerifier.create(service.reject(id, ApprovalEntitlement.Decider.of("admin@zte", java.util.List.of("ADMIN"))))
                .assertNext(saved -> assertThat(saved.status()).isEqualTo("REJECTED"))
                .verifyComplete();

        verify(forwardService, never()).execute(anyString(), any());

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
