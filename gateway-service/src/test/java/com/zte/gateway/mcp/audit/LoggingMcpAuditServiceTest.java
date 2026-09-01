package com.zte.gateway.mcp.audit;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link LoggingMcpAuditService} — specifically ADR-017's
 * unification of MCP audit events into {@code request_logs} via {@link
 * RequestLogAuditService}. Persistence happens on a background subscriber
 * ({@code Schedulers.boundedElastic()}), so assertions use Mockito's
 * {@code timeout(...)} mode, same precedent as {@code RequestLogAuditServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class LoggingMcpAuditServiceTest {

    @Mock RequestLogAuditService requestLogAuditService;

    @Test
    void allowedEvent_persistsAsAllowWith200() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "agent-a", "echo", "ALLOWED", Instant.now(), "session-1", null,
                "trace-abc", "203.0.113.7", "test-agent/1.0", "agent-a-subject-uuid", "{\"text\":\"hi\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        // ADR-017 unification: trace_id is the real HTTP X-Request-Id, not the MCP
        // session id — the session id is preserved in message instead (below).
        assertThat(logged.traceId()).isEqualTo("trace-abc");
        assertThat(logged.clientIp()).isEqualTo("203.0.113.7");
        assertThat(logged.userAgent()).isEqualTo("test-agent/1.0");
        assertThat(logged.originalUserObo()).isEqualTo("agent-a-subject-uuid");
        assertThat(logged.agentId()).isEqualTo("agent-a");
        assertThat(logged.toolName()).isEqualTo("echo");
        assertThat(logged.initiatorClient()).isEqualTo("agent-a");
        assertThat(logged.path()).isEqualTo("/message");
        assertThat(logged.httpMethod()).isEqualTo("POST");
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(logged.decisionEffect()).isEqualTo("ALLOW");
        assertThat(logged.targetService()).isEqualTo("test-mcp-backend");
        assertThat(logged.message()).isEqualTo("Session: session-1. Args: {\"text\":\"hi\"}");
    }

    @Test
    void sseOpenedEvent_persistsAsAllowWith200OnSseGetPath() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "agent-a", null, "SSE_OPENED", Instant.now(), "session-3", null,
                "trace-open", "203.0.113.9", "test-agent/3.0", "agent-a-subject-uuid", null));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.traceId()).isEqualTo("trace-open");
        assertThat(logged.agentId()).isEqualTo("agent-a");
        assertThat(logged.toolName()).isNull();
        assertThat(logged.path()).isEqualTo("/sse");
        assertThat(logged.httpMethod()).isEqualTo("GET");
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(logged.decisionEffect()).isEqualTo("ALLOW");
        assertThat(logged.message()).isEqualTo("Session: session-3");
    }

    @Test
    void deniedEvent_persistsAsDenyWith403AndReasonAppendedToSessionInMessage() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "agent-b", "delete_all", "DENIED", Instant.now(),
                "session-2", "tool not granted to this agent",
                "trace-xyz", "203.0.113.8", "test-agent/2.0", "agent-b-subject-uuid", "{\"id\":\"deal-1\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.traceId()).isEqualTo("trace-xyz");
        assertThat(logged.statusCode()).isEqualTo(403);
        assertThat(logged.decisionEffect()).isEqualTo("DENY");
        assertThat(logged.message())
                .isEqualTo("Session: session-2. tool not granted to this agent. Args: {\"id\":\"deal-1\"}");
    }

    /** Stage 1 (ADR-019): a held call is neither an ALLOW nor a DENY — 202, decisionEffect HOLD. */
    @Test
    void heldEvent_persistsAsHoldWith202() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "crm-account-health-emea-01", "send_email", "HELD", Instant.now(),
                "session-4", "held for human approval", "trace-hold", "203.0.113.10", "test-agent/4.0",
                "crm-subject-uuid", "{\"to\":\"rep@nordwind.example\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.statusCode()).isEqualTo(202);
        assertThat(logged.decisionEffect()).isEqualTo("HOLD");
    }

    /** A human-rejected hold is audited as a DENY — it's now the reviewer's decision, not the policy engine's. */
    @Test
    void rejectedEvent_persistsAsDenyWith403() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "crm-account-health-emea-01", "send_email", "REJECTED",
                Instant.now(), "session-4", "Rejected by admin@zte", "trace-hold", "203.0.113.10",
                "test-agent/4.0", "crm-subject-uuid", "{\"to\":\"rep@nordwind.example\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.statusCode()).isEqualTo(403);
        assertThat(logged.decisionEffect()).isEqualTo("DENY");
    }

    /**
     * An expiry is a DENY with its own status code (ADR-034). Pinned because the
     * mapping's default branch is ALLOW: when EXPIRED was first introduced it fell
     * through to that branch, and the audit trail recorded a call that never ran as
     * one that was permitted. Any future terminal state will do the same unless it
     * is added to the mapping deliberately.
     */
    @Test
    void expiredEvent_persistsAsDenyWith408_notAsAnAllow() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "crm-account-health-emea-01", "send_email", "EXPIRED",
                Instant.now(), "session-9", "Expired undecided at 2026-09-01T19:01:30Z", "trace-hold",
                "203.0.113.10", "test-agent/4.0", "crm-subject-uuid", "{\"to\":\"rep@nordwind.example\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.decisionEffect()).isEqualTo("DENY");
        assertThat(logged.statusCode()).isEqualTo(408);
        assertThat(logged.message()).contains("Expired undecided");
    }

    /** A human-approved hold is audited as an ALLOW. */
    @Test
    void approvedEvent_persistsAsAllowWith200() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService, "test-mcp-backend");

        service.record(new McpAuditEvent("1234", "crm-account-health-emea-01", "send_email", "APPROVED",
                Instant.now(), "session-4", "Approved by admin@zte", "trace-hold", "203.0.113.10",
                "test-agent/4.0", "crm-subject-uuid", "{\"to\":\"rep@nordwind.example\"}"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(logged.decisionEffect()).isEqualTo("ALLOW");
    }
}
