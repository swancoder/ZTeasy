package com.zte.gateway.mcp.approval;

import java.util.UUID;

/** Thrown by {@link PendingApprovalService#decide} when {@code id} has already been approved or rejected. */
public class ApprovalAlreadyDecidedException extends RuntimeException {
    public ApprovalAlreadyDecidedException(UUID id, String currentStatus) {
        super("Approval '" + id + "' was already decided (status=" + currentStatus + ")");
    }
}
