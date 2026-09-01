package com.zte.gateway.mcp.approval;

import java.time.Instant;
import java.util.UUID;

/**
 * Thrown when someone decides an approval whose deadline has passed (ADR-034).
 *
 * <p>Raised on the decision path as well as by the sweeper, because a row is past
 * its deadline the moment the clock says so — not the moment a timer notices.
 */
public class ApprovalExpiredException extends RuntimeException {
    public ApprovalExpiredException(UUID id, Instant expiresAt) {
        super("Approval '" + id + "' expired at " + expiresAt + " and can no longer be decided");
    }
}
