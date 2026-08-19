package com.zte.gateway.governance;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Unit tests for {@link GovernanceService} (Stage 4, ADR-021). */
@ExtendWith(MockitoExtension.class)
class GovernanceServiceTest {

    @Mock RequestLogRepository repository;

    private GovernanceService newService() {
        return new GovernanceService(repository);
    }

    private static RequestLog mcpRow(String agentId, String toolName, String decisionEffect, Instant timestamp) {
        return new RequestLog(null, timestamp, "trace-1", "203.0.113.1", "agent/1.0", "1234", agentId, toolName,
                "/message", 200, "msg", agentId, agentId, "hubspot-mcp", "POST", decisionEffect);
    }

    @Test
    void agentActivity_countsPerAgentByDecisionEffect() {
        GovernanceService svc = newService();
        Instant now = Instant.now();
        when(repository.findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc(any())).thenReturn(Flux.just(
                mcpRow("agent-a", "get_deals", "ALLOW", now),
                mcpRow("agent-a", "get_deals", "ALLOW", now.minusSeconds(60)),
                mcpRow("agent-a", "delete_deal", "DENY", now.minusSeconds(30)),
                mcpRow("crm-account-health-emea-01", "send_email", "HOLD", now.minusSeconds(10))));

        StepVerifier.create(svc.agentActivity(24))
                .assertNext(summaries -> {
                    assertThat(summaries).hasSize(2);
                    AgentActivitySummary agentA = summaries.stream()
                            .filter(s -> s.agentId().equals("agent-a")).findFirst().orElseThrow();
                    assertThat(agentA.allowCount()).isEqualTo(2);
                    assertThat(agentA.denyCount()).isEqualTo(1);
                    assertThat(agentA.holdCount()).isEqualTo(0);
                    assertThat(agentA.lastActivity()).isEqualTo(now);

                    AgentActivitySummary crmAgent = summaries.stream()
                            .filter(s -> s.agentId().equals("crm-account-health-emea-01")).findFirst().orElseThrow();
                    assertThat(crmAgent.holdCount()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void agentActivity_noRows_isEmptyList() {
        GovernanceService svc = newService();
        when(repository.findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc(any())).thenReturn(Flux.empty());

        StepVerifier.create(svc.agentActivity(24))
                .assertNext(summaries -> assertThat(summaries).isEmpty())
                .verifyComplete();
    }

    @Test
    void outOfPolicyAttempts_delegatesToRepository() {
        GovernanceService svc = newService();
        RequestLog denied = mcpRow("agent-b", "delete_deal", "DENY", Instant.now());
        when(repository.findTop50ByAgentIdIsNotNullAndDecisionEffectOrderByTimestampDesc("DENY"))
                .thenReturn(Flux.just(denied));

        StepVerifier.create(svc.outOfPolicyAttempts())
                .assertNext(rows -> assertThat(rows).containsExactly(denied))
                .verifyComplete();
    }

    @Test
    void report_combinesActivityAndOutOfPolicyAttempts() {
        GovernanceService svc = newService();
        when(repository.findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc(any()))
                .thenReturn(Flux.just(mcpRow("agent-a", "get_deals", "ALLOW", Instant.now())));
        when(repository.findTop50ByAgentIdIsNotNullAndDecisionEffectOrderByTimestampDesc("DENY"))
                .thenReturn(Flux.empty());

        StepVerifier.create(svc.report(24))
                .assertNext(report -> {
                    assertThat(report.windowHours()).isEqualTo(24);
                    assertThat(report.agentActivity()).hasSize(1);
                    assertThat(report.outOfPolicyAttempts()).isEmpty();
                    assertThat(report.generatedAt()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
                })
                .verifyComplete();
    }
}
