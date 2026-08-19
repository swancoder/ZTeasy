package com.zte.gateway.mcp.acap;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory per-agent-per-metric usage counter with a daily reset
 * (Stage 6, ADR-022) — backs {@code AcapScopeEvaluator#checkThresholds}'s
 * {@code followup_drafts_per_day}-style limits. Single-instance,
 * process-local, lost on restart — matches this codebase's established
 * demo-scale posture for in-memory state ({@code McpSessionManager},
 * {@code LoggingMcpAuditService}'s sink) rather than a new DB table for
 * something that's explicitly informational (Stage 6's "no enforcement
 * beyond escalating to HOLD" framing).
 */
@Component
public class AcapThresholdTracker {

    private final Map<String, Map<String, AtomicInteger>> counts = new ConcurrentHashMap<>();
    private volatile LocalDate resetDate = LocalDate.now();

    /** Increments and returns the new count for {@code agentId}/{@code metric}, resetting everything first if the day has rolled over. */
    public synchronized int incrementAndGet(String agentId, String metric) {
        rolloverIfNewDay();
        return counts.computeIfAbsent(agentId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(metric, m -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /** Read-only — the Admin Console's current usage display; never increments. */
    public synchronized int currentCount(String agentId, String metric) {
        rolloverIfNewDay();
        Map<String, AtomicInteger> agentCounts = counts.get(agentId);
        if (agentCounts == null) {
            return 0;
        }
        AtomicInteger counter = agentCounts.get(metric);
        return counter == null ? 0 : counter.get();
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(resetDate)) {
            counts.clear();
            resetDate = today;
        }
    }
}
