package com.zte.gateway.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides whether a given human may decide a given held call (Stage 34, ADR-034).
 *
 * <p>The rule that held the call may name an approver in {@code routeTo}, using the
 * same URN vocabulary as a policy rule's {@code source}. When it does, only a
 * matching human may approve or reject; when it doesn't, ADR-026's posture stands
 * and any interactive user may. Unrouted is therefore the permissive case on
 * purpose — routing is something a policy author opts into for the calls that
 * warrant a named owner, not a wall around the whole queue.
 *
 * <p>Everyone still <em>sees</em> every item. Hiding a held call from the people who
 * can't decide it would hide the fact that the system held anything at all, which is
 * the one thing the queue exists to show.
 */
@Component
public class ApprovalEntitlement {

    private static final Logger log = LoggerFactory.getLogger(ApprovalEntitlement.class);

    /** A human's identity as the token presents it — username plus realm roles. */
    public record Decider(String username, List<String> roles) {
        public static Decider of(String username, List<String> roles) {
            return new Decider(username == null ? "unknown" : username, roles == null ? List.of() : roles);
        }
    }

    /**
     * @return empty when the decider may act, otherwise the reason they may not —
     *         phrased for the person reading it, since it reaches the UI verbatim.
     */
    public java.util.Optional<String> refusalReason(PendingApproval approval, Decider decider) {
        String routeTo = approval.routeTo();
        if (routeTo == null || routeTo.isBlank()) {
            return java.util.Optional.empty();
        }
        String urn = routeTo.trim();

        if (urn.startsWith("role:")) {
            String required = urn.substring("role:".length());
            return decider.roles().contains(required)
                    ? java.util.Optional.empty()
                    : java.util.Optional.of("This approval is routed to the '" + required
                            + "' role, which you do not hold");
        }
        if (urn.startsWith("user:")) {
            String required = urn.substring("user:".length());
            return required.equals(decider.username())
                    ? java.util.Optional.empty()
                    : java.util.Optional.of("This approval is routed to " + required + " specifically");
        }
        if (urn.startsWith("group:")) {
            // A realm JWT carries realm_access.roles and nothing about groups, so a
            // group URN cannot be evaluated here at all. Refusing loudly beats
            // treating it as "no match" — a rule that silently never matches anyone
            // reads, from the queue, exactly like a rule that works.
            log.warn("[ZTE-APPROVAL] rule routes to '{}', but group membership is not in the token — refusing everyone."
                    + " Use role: or user: until group claims are mapped (ADR-034).", urn);
            return java.util.Optional.of("This approval is routed to " + urn
                    + ", and group membership is not available to the gateway — no one can decide it as written");
        }
        // A bare name is a role name, matching how policy rules accept "ADMIN"
        // alongside "role:ADMIN" (ADR-014).
        return decider.roles().contains(urn)
                ? java.util.Optional.empty()
                : java.util.Optional.of("This approval is routed to '" + urn + "', which you do not hold");
    }

    public boolean canDecide(PendingApproval approval, Decider decider) {
        return refusalReason(approval, decider).isEmpty();
    }
}
