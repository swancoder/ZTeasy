package com.zte.gateway.mcp.approval;

/**
 * Thrown when a human may see a held call but not decide it, because the rule that
 * held it routed the decision elsewhere (ADR-034).
 *
 * <p>The message is written for the person who will read it in the Approval Center,
 * and is passed through to them verbatim.
 */
public class ApprovalNotRoutedToYouException extends RuntimeException {
    public ApprovalNotRoutedToYouException(String reason) {
        super(reason);
    }
}
