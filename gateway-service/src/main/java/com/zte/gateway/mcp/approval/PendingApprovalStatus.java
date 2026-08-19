package com.zte.gateway.mcp.approval;

/** Lifecycle of a {@link PendingApproval} row (Stage 1, ADR-019). */
public enum PendingApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
