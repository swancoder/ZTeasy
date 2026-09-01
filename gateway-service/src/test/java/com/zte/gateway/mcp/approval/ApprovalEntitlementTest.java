package com.zte.gateway.mcp.approval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may decide a held call (Stage 34, ADR-034).
 *
 * <p>The permissive case is the important one to pin down: an unrouted approval
 * must stay decidable by any interactive user, because that is ADR-026's posture
 * and routing was added as an opt-in, not as a lockdown.
 */
class ApprovalEntitlementTest {

    private final ApprovalEntitlement entitlement = new ApprovalEntitlement();

    private PendingApproval routedTo(String routeTo) {
        return PendingApproval.requested("s1", "agent-a", "send_email", "1", "{}", routeTo,
                "held", "trace", "127.0.0.1", "curl", "Agent A", Duration.ofHours(24));
    }

    @Test
    void unroutedApproval_isDecidableByAnyone() {
        assertThat(entitlement.canDecide(routedTo(null),
                ApprovalEntitlement.Decider.of("zte-test-user", List.of("USER")))).isTrue();
        assertThat(entitlement.canDecide(routedTo("  "),
                ApprovalEntitlement.Decider.of("zte-test-user", List.of("USER")))).isTrue();
    }

    @Test
    void roleUrn_admitsTheHolderAndRefusesEveryoneElse() {
        PendingApproval approval = routedTo("role:APPROVER");

        assertThat(entitlement.canDecide(approval,
                ApprovalEntitlement.Decider.of("zte-dpo", List.of("DPO", "USER", "APPROVER")))).isTrue();
        assertThat(entitlement.canDecide(approval,
                ApprovalEntitlement.Decider.of("zte-test-user", List.of("USER")))).isFalse();
    }

    /** ADR-014's bare-name form works for routing too, not just for a rule's source. */
    @Test
    void bareRoleName_behavesLikeTheUrnForm() {
        assertThat(entitlement.canDecide(routedTo("APPROVER"),
                ApprovalEntitlement.Decider.of("zte-dpo", List.of("APPROVER")))).isTrue();
        assertThat(entitlement.canDecide(routedTo("APPROVER"),
                ApprovalEntitlement.Decider.of("zte-test-user", List.of("USER")))).isFalse();
    }

    @Test
    void userUrn_admitsOnlyThatUsername() {
        PendingApproval approval = routedTo("user:zte-dpo");

        assertThat(entitlement.canDecide(approval,
                ApprovalEntitlement.Decider.of("zte-dpo", List.of()))).isTrue();
        // Holding every role in the realm is not the same as being the named person.
        assertThat(entitlement.canDecide(approval,
                ApprovalEntitlement.Decider.of("zte-admin", List.of("ADMIN", "APPROVER")))).isFalse();
    }

    /**
     * A realm JWT carries no group membership, so a group-routed approval is
     * refused to everyone — deliberately, and with the reason spelled out. The
     * alternative (treat it as "no match" and stay silent) makes a rule that can
     * never work look identical to one that does.
     */
    @Test
    void groupUrn_refusesEveryoneAndSaysWhy() {
        PendingApproval approval = routedTo("group:compliance");

        assertThat(entitlement.refusalReason(approval,
                ApprovalEntitlement.Decider.of("zte-dpo", List.of("APPROVER", "DPO"))))
                .isPresent()
                .get().asString().contains("group membership is not available");
    }

    @Test
    void refusalReason_namesTheRoleThePersonIsMissing() {
        assertThat(entitlement.refusalReason(routedTo("role:APPROVER"),
                ApprovalEntitlement.Decider.of("zte-test-user", List.of("USER"))))
                .get().asString().contains("APPROVER");
    }
}
