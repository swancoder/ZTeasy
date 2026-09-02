package com.zte.gateway.mcp.approval;

import com.zte.gateway.identity.IdpIdentityRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Turns "who owns this decision" into "who gets told" (Stage 35, ADR-035).
 *
 * <p>These are deliberately different questions. A held call with no {@code routeTo}
 * may be decided by any interactive user (ADR-026/ADR-034) — but notifying everyone
 * who <em>could</em> act is how an item ends up expiring with six people each
 * assuming one of the others had it. So an unrouted call still has an addressee:
 * {@code zte.approvals.default-notify}, {@code role:APPROVER} by default. Permission
 * stays broad; responsibility is named.
 */
@Component
public class ApprovalAudience {

    private static final Logger log = LoggerFactory.getLogger(ApprovalAudience.class);

    /**
     * @param urn          who this was addressed to
     * @param members      usernames behind that URN at send time — for the audit row and the
     *                     message, so "notified role:APPROVER" can be checked against real people
     * @param deliverable  false when the URN cannot be resolved to anyone at all
     */
    public record Audience(String urn, List<String> members, boolean deliverable) {}

    private final IdpIdentityRelationRepository relations;
    private final String defaultNotify;

    public ApprovalAudience(IdpIdentityRelationRepository relations,
                             @Value("${zte.approvals.default-notify:role:APPROVER}") String defaultNotify) {
        this.relations = relations;
        this.defaultNotify = defaultNotify == null ? "" : defaultNotify.trim();
    }

    /** The URN a held call is addressed to: its own route, or the configured default. */
    public String effectiveUrn(PendingApproval approval) {
        String routeTo = approval.routeTo();
        return (routeTo == null || routeTo.isBlank()) ? defaultNotify : routeTo.trim();
    }

    public Mono<Audience> resolve(PendingApproval approval) {
        String urn = effectiveUrn(approval);
        if (urn.isEmpty()) {
            return Mono.just(new Audience("", List.of(), false));
        }
        if (urn.startsWith("user:")) {
            return Mono.just(new Audience(urn, List.of(urn.substring("user:".length())), true));
        }
        if (urn.startsWith("group:")) {
            // Same limit as entitlement: a realm token carries no group claim, so a
            // group-addressed item has no one this gateway can name.
            log.warn("[ZTE-APPROVAL] cannot address '{}' — group membership is not synced (ADR-035)", urn);
            return Mono.just(new Audience(urn, List.of(), false));
        }
        String role = urn.startsWith("role:") ? urn.substring("role:".length()) : urn;
        return relations.findUsernamesWithRole(role)
                .collectList()
                .map(members -> {
                    if (members.isEmpty()) {
                        // A live-fire version of the orphaned-rule check: a rule may route to
                        // a role nobody holds, and then the call is addressed to no one.
                        log.warn("[ZTE-APPROVAL] '{}' resolves to nobody — the held call has no addressee", urn);
                    }
                    return new Audience(urn, members, !members.isEmpty());
                });
    }
}
