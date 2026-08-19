package com.zte.gateway.governance;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Business layer for the governance dashboard (Stage 4, ADR-021): per-agent
 * ALLOW/HOLD/DENY activity and a live "out-of-policy attempts" feed, both
 * read from the existing {@code request_logs} audit trail (ADR-013/ADR-017)
 * — no new table, this stage is read-only reporting over data every prior
 * stage already writes. The Stage 1 "Approvals" tab already covers the
 * pending-queue half of "governance"; this is the historical/reporting half.
 */
@Service
public class GovernanceService {

    private final RequestLogRepository repository;

    public GovernanceService(RequestLogRepository repository) {
        this.repository = repository;
    }

    /** One row per agent that made at least one MCP call in the last {@code hours}, sorted by agent id. */
    public Mono<List<AgentActivitySummary>> agentActivity(int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return repository.findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc(since)
                .collectMultimap(RequestLog::agentId)
                .map(byAgent -> byAgent.entrySet().stream()
                        .map(entry -> summarize(entry.getKey(), entry.getValue()))
                        .sorted(Comparator.comparing(AgentActivitySummary::agentId))
                        .toList());
    }

    private AgentActivitySummary summarize(String agentId, Collection<RequestLog> rows) {
        long allow = rows.stream().filter(r -> "ALLOW".equals(r.decisionEffect())).count();
        long deny = rows.stream().filter(r -> "DENY".equals(r.decisionEffect())).count();
        long hold = rows.stream().filter(r -> "HOLD".equals(r.decisionEffect())).count();
        Instant lastActivity = rows.stream().map(RequestLog::timestamp).max(Instant::compareTo).orElse(null);
        return new AgentActivitySummary(agentId, allow, deny, hold, lastActivity);
    }

    /** Latest 50 MCP-agent denials (including a human's REJECTED-after-hold, mapped to DENY — ADR-019), newest first. */
    public Mono<List<RequestLog>> outOfPolicyAttempts() {
        return repository.findTop50ByAgentIdIsNotNullAndDecisionEffectOrderByTimestampDesc("DENY").collectList();
    }

    public Mono<GovernanceReport> report(int hours) {
        return Mono.zip(agentActivity(hours), outOfPolicyAttempts())
                .map(tuple -> new GovernanceReport(Instant.now(), hours, tuple.getT1(), tuple.getT2()));
    }
}
