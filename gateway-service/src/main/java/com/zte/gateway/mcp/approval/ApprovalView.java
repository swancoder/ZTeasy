package com.zte.gateway.mcp.approval;

import java.time.Instant;

/**
 * A held call as a human sees it (Stage 34, ADR-034): the stored row plus the two
 * things that depend on <em>who is asking</em> and <em>when</em> — whether this
 * viewer may decide it, and how long is left.
 *
 * <p>Computed per request rather than stored: entitlement depends on the caller's
 * token and the remaining time depends on the clock, so neither can be persisted
 * without being wrong by the time it is read.
 */
public record ApprovalView(
        java.util.UUID id,
        String sessionId,
        String agentId,
        String toolName,
        String argumentsJson,
        String routeTo,
        String reason,
        String status,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        String traceId,
        String displayIdentity,
        boolean canDecide,
        String refusalReason,
        long secondsRemaining,
        // ADR-035. Deliberately separate from canDecide: an unrouted call is
        // decidable by anyone but addressed to a named audience, so that it has
        // an owner instead of six people assuming someone else has it.
        String addressedTo,
        boolean addressedToYou,
        String notificationStatus,
        Instant notifiedAt,
        // Which contact this was (ADR-036): a bare timestamp cannot tell an
        // operator whether anyone has been nudged since the item was raised.
        String notificationKind,
        String notificationStage
) {

    public static ApprovalView of(PendingApproval a, ApprovalEntitlement entitlement, ApprovalAudience audience,
                                   ApprovalEntitlement.Decider decider, ApprovalNotification notification,
                                   Instant now) {
        String refusal = entitlement.refusalReason(a, decider).orElse(null);
        long remaining = a.expiresAt() == null ? 0 : Math.max(0, a.expiresAt().getEpochSecond() - now.getEpochSecond());
        boolean expired = a.isExpired(now);
        String addressedTo = audience.effectiveUrn(a);
        return new ApprovalView(a.id(), a.sessionId(), a.agentId(), a.toolName(), a.argumentsJson(), a.routeTo(),
                a.reason(), expired ? PendingApprovalStatus.EXPIRED.name() : a.status(), a.requestedAt(),
                a.expiresAt(), a.decidedAt(), a.decidedBy(), a.traceId(), a.displayIdentity(),
                refusal == null && !expired, expired ? "This approval has expired" : refusal, remaining,
                addressedTo, entitlement.matches(addressedTo, decider),
                notification == null ? null : notification.status(),
                notification == null ? null : notification.createdAt(),
                notification == null ? null : notification.kind(),
                notification == null ? null : notification.stage());
    }
}
