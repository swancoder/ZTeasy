package com.zte.gateway.dashboard;

import com.zte.gateway.governance.AgentActivitySummary;
import com.zte.gateway.governance.GovernanceService;
import com.zte.gateway.inventory.InventoryService;
import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.approval.PendingApprovalService;
import com.zte.gateway.metering.LlmMeteringService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Aggregates the executive dashboard's figures (Stage 29, ADR-029).
 *
 * <p>Every number here is derived from data the system already produces —
 * the audit trail, the approval queue, the ACAP profiles, the registry and
 * (new in this stage) the metering table. Nothing is invented for display:
 * where an audience expects a figure this system genuinely doesn't measure,
 * the API says so via {@code instrumented=false} rather than shipping a
 * plausible number (see {@link SpendPanel}).
 */
@Service
public class DashboardService {

    private final GovernanceService governance;
    private final PendingApprovalService approvals;
    private final AcapProfileStore acapProfiles;
    private final InventoryService inventory;
    private final LlmMeteringService metering;

    public DashboardService(GovernanceService governance, PendingApprovalService approvals,
                            AcapProfileStore acapProfiles, InventoryService inventory,
                            LlmMeteringService metering) {
        this.governance = governance;
        this.approvals = approvals;
        this.acapProfiles = acapProfiles;
        this.inventory = inventory;
        this.metering = metering;
    }

    /** The shared KPI tiles every audience sees at the top of the page. */
    public Mono<SummaryPanel> summary(int hours) {
        Mono<List<AgentActivitySummary>> activity = governance.agentActivity(hours);
        Mono<Long> pending = approvals.listPending().count();
        Mono<LlmMeteringService.SpendTotals> spend = metering.totals(hours);

        return Mono.zip(activity, pending, spend).map(t -> {
            List<AgentActivitySummary> agents = t.getT1();
            long allowed = agents.stream().mapToLong(AgentActivitySummary::allowCount).sum();
            long held = agents.stream().mapToLong(AgentActivitySummary::holdCount).sum();
            long denied = agents.stream().mapToLong(AgentActivitySummary::denyCount).sum();

            List<AcapProfile> profiles = acapProfiles.all();
            long overdue = profiles.stream().filter(DashboardService::isReauthOverdue).count();

            // "Governed" = agents that both showed up in the audit trail and
            // have an ACAP profile; the denominator is every agent seen at all.
            // Anything else would be counting configuration, not reality.
            long seen = agents.size();
            long governed = agents.stream()
                    .filter(a -> acapProfiles.find(a.agentId()).isPresent()
                            || acapProfiles.find(stripServiceAccountPrefix(a.agentId())).isPresent())
                    .count();

            return new SummaryPanel(
                    governed, seen,
                    allowed + held + denied,
                    new GateDecisions(allowed, held, denied),
                    t.getT2(),
                    profiles.size() - overdue, profiles.size(), overdue,
                    t.getT3().costMicros(), t.getT3().inputTokens() + t.getT3().outputTokens(),
                    t.getT3().calls());
        });
    }

    /** CFO view: spend over time, per agent, with token counts. */
    public Mono<SpendPanel> spend(int days) {
        return Mono.zip(metering.dailySpend(days), metering.spendByAgent(days * 24), metering.totals(days * 24))
                .map(t -> new SpendPanel(
                        t.getT1(), t.getT2(), t.getT3(),
                        // Honest emptiness: the tiles render "not yet reported"
                        // instead of €0 when nothing has ever been metered, so a
                        // silent integration gap can't read as "we spent nothing".
                        t.getT3().calls() > 0));
    }

    /** CTO view: per-agent gate activity plus the registry's health snapshot. */
    public Mono<OperationsPanel> operations(int hours) {
        return Mono.zip(governance.agentActivity(hours), inventory.list())
                .map(t -> new OperationsPanel(t.getT1(), t.getT2()));
    }

    /** Board/Risk view: risk tiers, overdue re-authorizations, recent refusals. */
    public Mono<RiskPanel> risk(int hours) {
        return governance.outOfPolicyAttempts().map(outOfPolicy -> {
            List<AcapProfileRisk> profiles = acapProfiles.all().stream()
                    .map(p -> new AcapProfileRisk(
                            p.agentId(),
                            Optional.ofNullable(p.agent()).map(a -> a.name()).orElse(p.agentId()),
                            Optional.ofNullable(p.risk()).map(r -> r.euAiActClass()).orElse(null),
                            Optional.ofNullable(p.risk()).map(r -> r.internalTier()).orElse(null),
                            Optional.ofNullable(p.agent()).map(a -> a.reauthDue()).orElse(null),
                            isReauthOverdue(p)))
                    .toList();
            return new RiskPanel(profiles, outOfPolicy);
        });
    }

    /**
     * DPO view: what each agent is actually scoped to read — territory,
     * resources and the exact fields — which is the data-protection question
     * the ACAP profiles already answer, surfaced instead of buried in YAML.
     */
    public Mono<DataProtectionPanel> dataProtection() {
        List<AgentDataScope> scopes = acapProfiles.all().stream()
                .map(p -> new AgentDataScope(
                        p.agentId(),
                        p.territory(),
                        p.writeAllowed(),
                        p.readGrants().stream()
                                .map(g -> new ResourceFields(g.resource(), g.fields()))
                                .toList()))
                .toList();
        return Mono.just(new DataProtectionPanel(scopes));
    }

    /**
     * An ACAP profile is overdue once its {@code reauthDue} date is in the
     * past — the same rule the Governance tab's red badge already uses
     * (ADR-022); re-implemented here rather than shared because that one
     * lives in the UI. An unparseable or absent date is never overdue.
     */
    static boolean isReauthOverdue(AcapProfile profile) {
        String due = Optional.ofNullable(profile.agent()).map(a -> a.reauthDue()).orElse(null);
        if (due == null || due.isBlank()) {
            return false;
        }
        try {
            return LocalDate.parse(due).isBefore(LocalDate.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Keycloak reports a client-credentials caller as
     * {@code service-account-<clientId>} in some claims; ACAP profiles are
     * keyed by the bare client id. Strip the prefix so an agent isn't counted
     * as ungoverned purely because of which claim the audit row captured.
     */
    static String stripServiceAccountPrefix(String agentId) {
        return agentId != null && agentId.startsWith("service-account-")
                ? agentId.substring("service-account-".length())
                : agentId;
    }
}
