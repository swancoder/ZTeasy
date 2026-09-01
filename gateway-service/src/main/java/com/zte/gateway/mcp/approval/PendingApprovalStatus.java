package com.zte.gateway.mcp.approval;

/** Lifecycle of a {@link PendingApproval} row (Stage 1, ADR-019). */
public enum PendingApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /**
     * Nobody decided in time (ADR-034). A terminal state like the other two,
     * and audited like them — the difference between "expired" and "silently
     * dropped" is the whole point of having it.
     */
    EXPIRED
}
