package com.zte.gateway.mcp.approval;

import java.util.UUID;

/** Thrown by {@link PendingApprovalService#decide} when {@code id} isn't a known pending approval. */
public class ApprovalNotFoundException extends RuntimeException {
    public ApprovalNotFoundException(UUID id) {
        super("No pending approval with id '" + id + "'");
    }
}
