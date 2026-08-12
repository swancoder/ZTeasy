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
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService);

        service.record(new McpAuditEvent("1234", "agent-a", "echo", "ALLOWED", Instant.now(), "session-1", null));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.traceId()).isEqualTo("session-1");
        assertThat(logged.agentId()).isEqualTo("agent-a");
        assertThat(logged.toolName()).isEqualTo("echo");
        assertThat(logged.initiatorClient()).isEqualTo("agent-a");
        assertThat(logged.path()).isEqualTo("/message");
        assertThat(logged.httpMethod()).isEqualTo("POST");
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(logged.decisionEffect()).isEqualTo("ALLOW");
        assertThat(logged.targetService()).isEqualTo("mcp");
        assertThat(logged.message()).isNull();
    }

    @Test
    void deniedEvent_persistsAsDenyWith403AndReasonAsMessage() {
        LoggingMcpAuditService service = new LoggingMcpAuditService(requestLogAuditService);

        service.record(new McpAuditEvent("1234", "agent-b", "delete_all", "DENIED", Instant.now(),
                "session-2", "tool not granted to this agent"));

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogAuditService, timeout(2000)).record(captor.capture());
        RequestLog logged = captor.getValue();
        assertThat(logged.statusCode()).isEqualTo(403);
        assertThat(logged.decisionEffect()).isEqualTo("DENY");
        assertThat(logged.message()).isEqualTo("tool not granted to this agent");
    }
}
