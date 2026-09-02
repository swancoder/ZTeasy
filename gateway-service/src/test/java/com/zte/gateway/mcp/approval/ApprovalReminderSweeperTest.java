package com.zte.gateway.mcp.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which thresholds a held call has passed (Stage 36, ADR-036). */
@ExtendWith(MockitoExtension.class)
class ApprovalReminderSweeperTest {

    @Mock PendingApprovalService service;
    @Mock ApprovalNotifier       notifier;

    private static final Instant RAISED = Instant.parse("2026-09-02T10:00:00Z");

    private PendingApproval approval(Duration lifetime, String status) {
        return new PendingApproval(UUID.randomUUID(), "s", "agent-a", "send_email", "1", "{}", null,
                "held", status, RAISED, RAISED.plus(lifetime), null, null, "trace", null, null, null);
    }

    private ApprovalReminderSweeper sweeper(String fractions) {
        return new ApprovalReminderSweeper(service, notifier, fractions);
    }

    @Test
    void beforeTheThreshold_nothingIsDue() {
        assertThat(sweeper("0.5").due(approval(Duration.ofHours(24), "PENDING"), RAISED.plusSeconds(3600)))
                .isEmpty();
    }

    @Test
    void atAndAfterTheThreshold_theStageIsDue() {
        PendingApproval a = approval(Duration.ofHours(24), "PENDING");
        assertThat(sweeper("0.5").due(a, RAISED.plus(Duration.ofHours(12)))).containsExactly("0.5");
        assertThat(sweeper("0.5").due(a, RAISED.plus(Duration.ofHours(20)))).containsExactly("0.5");
    }

    /**
     * Every passed threshold is returned, not just the newest. Whether an older one
     * was already sent is the claim row's job — asking here would be a check-then-act
     * race against the other gateway instance.
     */
    @Test
    void multipleThresholds_allPassedOnesAreReturned() {
        PendingApproval a = approval(Duration.ofHours(24), "PENDING");
        assertThat(sweeper("0.5,0.9").due(a, RAISED.plus(Duration.ofHours(23))))
                .containsExactly("0.5", "0.9");
    }

    @Test
    void expiredItem_isNotReminded() {
        PendingApproval a = approval(Duration.ofHours(24), "PENDING");
        assertThat(sweeper("0.5").due(a, RAISED.plus(Duration.ofHours(25)))).isEmpty();
    }

    /** A misconfigured fraction disables itself, not the whole list. */
    @Test
    void unparseableFraction_isSkippedWithoutLosingTheOthers() {
        PendingApproval a = approval(Duration.ofHours(24), "PENDING");
        assertThat(sweeper("half,0.5").due(a, RAISED.plus(Duration.ofHours(13))))
                .containsExactly("0.5");
    }

    @Test
    void noDeadline_meansNoReminder() {
        PendingApproval noDeadline = new PendingApproval(UUID.randomUUID(), "s", "agent-a", "send_email", "1",
                "{}", null, "held", "PENDING", RAISED, null, null, null, "trace", null, null, null);
        assertThat(sweeper("0.5").due(noDeadline, RAISED.plus(Duration.ofDays(9)))).isEmpty();
    }
}
