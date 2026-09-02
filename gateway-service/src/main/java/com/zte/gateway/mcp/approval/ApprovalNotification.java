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
        @Column("created_at")     Instant createdAt
) {

    public static ApprovalNotification of(UUID approvalId, String audience, String recipients,
                                           Status status, String detail) {
        return new ApprovalNotification(null, approvalId, "WEBHOOK", audience, recipients,
                status.name(), detail, Instant.now());
    }

    /**
     * {@code SKIPPED} is recorded, not omitted: "no webhook is configured" is an
     * answer to "why did nobody hear about this", and an empty table would look
     * like a system that tried nothing for reasons unknown.
     */
    public enum Status { SENT, FAILED, SKIPPED }
}
