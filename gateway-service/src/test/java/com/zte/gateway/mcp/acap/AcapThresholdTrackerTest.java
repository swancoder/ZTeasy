package com.zte.gateway.mcp.acap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AcapThresholdTracker} (Stage 6, ADR-022). Daily-rollover behavior isn't
 * tested here — it depends on wall-clock date, which this class deliberately reads via
 * {@code LocalDate.now()} rather than accepting an injectable clock (demo-scale simplicity,
 * matching this codebase's other in-memory single-instance state). */
class AcapThresholdTrackerTest {

    private final AcapThresholdTracker tracker = new AcapThresholdTracker();

    @Test
    void firstIncrement_returnsOne() {
        assertThat(tracker.incrementAndGet("agent-a", "followup_drafts_per_day")).isEqualTo(1);
    }

    @Test
    void repeatedIncrements_accumulate() {
        tracker.incrementAndGet("agent-a", "followup_drafts_per_day");
        tracker.incrementAndGet("agent-a", "followup_drafts_per_day");
        assertThat(tracker.incrementAndGet("agent-a", "followup_drafts_per_day")).isEqualTo(3);
    }

    @Test
    void differentAgents_areIndependent() {
        tracker.incrementAndGet("agent-a", "followup_drafts_per_day");
        tracker.incrementAndGet("agent-a", "followup_drafts_per_day");
        assertThat(tracker.incrementAndGet("agent-b", "followup_drafts_per_day")).isEqualTo(1);
    }

    @Test
    void differentMetrics_forSameAgent_areIndependent() {
        tracker.incrementAndGet("agent-a", "metric-x");
        assertThat(tracker.incrementAndGet("agent-a", "metric-y")).isEqualTo(1);
    }

    @Test
    void currentCount_neverIncrements() {
        tracker.incrementAndGet("agent-a", "metric-x");
        assertThat(tracker.currentCount("agent-a", "metric-x")).isEqualTo(1);
        assertThat(tracker.currentCount("agent-a", "metric-x")).isEqualTo(1);
    }

    @Test
    void currentCount_unknownAgentOrMetric_isZero() {
        assertThat(tracker.currentCount("nobody", "metric-x")).isZero();
        tracker.incrementAndGet("agent-a", "metric-x");
        assertThat(tracker.currentCount("agent-a", "unknown-metric")).isZero();
    }
}
