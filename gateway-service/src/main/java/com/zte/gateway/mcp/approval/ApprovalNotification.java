package com.zte.gateway.mcp.approval;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** One delivery attempt for a held call's notification (Stage 35, ADR-035). */
@Table("approval_notifications")
public record ApprovalNotification(
        @Id                       UUID    id,
        @Column("approval_id")    UUID    approvalId,
                                  String  channel,
                                  String  audience,
                                  String  recipients,
                                  String  status,
                                  String  detail,
        @Column("created_at")     Instant createdAt,
                                  String  kind,
                                  String  stage
) {

    public static ApprovalNotification of(UUID approvalId, String audience, String recipients,
                                           Status status, String detail) {
        return new ApprovalNotification(null, approvalId, "WEBHOOK", audience, recipients,
                status.name(), detail, Instant.now(), Kind.RAISED.name(), null);
    }

    /**
     * A reminder's claim row, written <em>before</em> the message is sent (ADR-036).
     * Both gateway apps run the scheduler, and unlike expiry a reminder changes
     * nothing about the approval — so the unique index on (approval_id, stage) is
     * what stops two instances from both sending. The loser sees a duplicate key.
     */
    public static ApprovalNotification reminderClaim(UUID approvalId, String stage, String audience,
                                                      String recipients) {
        return new ApprovalNotification(null, approvalId, "WEBHOOK", audience, recipients,
                Status.CLAIMED.name(), "claimed, not yet sent", Instant.now(), Kind.REMINDER.name(), stage);
    }

    public ApprovalNotification settled(Status status, String detail) {
        return new ApprovalNotification(id, approvalId, channel, audience, recipients, status.name(), detail,
                createdAt, kind, stage);
    }

    /**
     * {@code SKIPPED} is recorded, not omitted: "no webhook is configured" is an
     * answer to "why did nobody hear about this", and an empty table would look
     * like a system that tried nothing for reasons unknown.
     */
    public enum Status { CLAIMED, SENT, FAILED, SKIPPED }

    public enum Kind { RAISED, REMINDER }
}
