package com.zte.gateway.mcp.acap;

import com.zte.gateway.mcp.policy.PolicyDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Argument/field-level tightening pass for an agent with an {@link
 * AcapProfile} (Stage 3, ADR-020) — the demo's core technical thesis: the
 * <em>same</em> tool name must be allowed or denied differently depending on
 * its arguments (territory, requested fields), not just its name.
 *
 * <p>Called from {@code YamlMcpPolicyEngine.evaluate()} only after the
 * coarse {@code agentMcpToolCalls}/{@code agentMcpToolHolds} pass has
 * already resolved to something other than DENY — this evaluator can only
 * <em>tighten</em> that decision to DENY, never loosen a DENY back to
 * ALLOW/HOLD, and never invent an ALLOW of its own. Returns {@link
 * Optional#empty()} — "no opinion, keep the existing decision" — for any
 * tool name it doesn't recognize the shape of (e.g. {@code send_email}/
 * {@code draft_followup}, which ACAP governs via {@code hold}, not {@code
 * scope}, and are already correctly handled by ADR-019's {@code
 * agentMcpToolHolds}).
 *
 * <p><b>Tool-name-to-resource convention:</b> {@code read_<resource>} (e.g.
 * {@code read_contacts} → resource {@code contacts}), {@code update_*}
 * (write-shaped), {@code export_*} (bulk-shaped) — matches the demo script's
 * own naming exactly (see {@code examples-from-vlad/demo-case-A-crm-hubspot.pdf}).
 * A real multi-tenant product would need an explicit tool→resource mapping
 * rather than a naming convention; deferred until a second demo needs it.
 *
 * <p><b>ACAP {@code never} list, realized as fixed reason labels, not
 * loaded data:</b> {@code bulk_export_contacts}/{@code change_record}/
 * {@code read_outside_territory}/{@code fields.deny} each correspond to
 * exactly one check below and are baked into that check's deny reason —
 * there's no separate {@code never[]} array to load or match against. See
 * ADR-020's Decision section for why this interpretation was chosen over a
 * literal field-by-field translation.
 *
 * <p><b>Thresholds (Stage 6, ADR-022):</b> a separate method, {@link
 * #checkThresholds}, called by {@code YamlMcpPolicyEngine} only when {@link
 * #tighten} didn't already produce a DENY — unlike {@code tighten}, this one
 * can <em>escalate</em> an ALLOW to HOLD (never invents an ALLOW, never
 * touches an existing DENY or HOLD). Kept as a distinct method rather than
 * folded into {@code tighten} because it answers a different question
 * ("has this agent used this tool too much today," not "does this specific
 * call violate scope") and has a side effect ({@link AcapThresholdTracker}
 * increments) {@code tighten}'s pure checks deliberately don't have.
 */
@Component
public class AcapScopeEvaluator {

    private static final String READ_PREFIX = "read_";
    private static final String UPDATE_PREFIX = "update_";
    private static final String EXPORT_PREFIX = "export_";

    private final AcapThresholdTracker thresholdTracker;

    public AcapScopeEvaluator(AcapThresholdTracker thresholdTracker) {
        this.thresholdTracker = thresholdTracker;
    }

    /**
     * @return a tightened DENY, or {@link Optional#empty()} to leave the caller's existing decision untouched
     */
    public Optional<PolicyDecision> tighten(AcapProfile profile, String toolName, Map<String, Object> arguments) {
        if (toolName.startsWith(EXPORT_PREFIX)) {
            return deny("Bulk/export calls are never permitted under agent '" + profile.agentId()
                    + "'s ACAP profile — bypasses territory and field scoping (never: bulk_export_contacts)");
        }
        if (toolName.startsWith(UPDATE_PREFIX)) {
            return profile.writeAllowed() ? Optional.empty() : deny("Record writes are not permitted under agent '"
                    + profile.agentId() + "'s ACAP profile — read-only (never: change_record)");
        }
        if (!toolName.startsWith(READ_PREFIX)) {
            return Optional.empty();
        }
        return tightenRead(profile, toolName.substring(READ_PREFIX.length()), arguments);
    }

    private Optional<PolicyDecision> tightenRead(AcapProfile profile, String resource, Map<String, Object> arguments) {
        Optional<AcapReadGrant> grant = profile.readGrants().stream()
                .filter(g -> resource.equals(g.resource()))
                .findFirst();
        if (grant.isEmpty()) {
            return deny("No scope.read grant for resource '" + resource + "' under agent '" + profile.agentId()
                    + "'s ACAP profile");
        }

        String requestedTerritory = String.valueOf(arguments.get("territory"));
        if (!profile.territory().equals(requestedTerritory)) {
            return deny("Territory '" + requestedTerritory + "' is outside agent '" + profile.agentId()
                    + "'s assigned territory '" + profile.territory() + "' (never: read_outside_territory)");
        }

        List<String> allowedFields = grant.get().fields();
        Object requestedFieldsRaw = arguments.get("fields");
        if (!allowedFields.isEmpty() && requestedFieldsRaw instanceof List<?> requestedFields) {
            List<String> disallowed = requestedFields.stream()
                    .map(String::valueOf)
                    .filter(field -> !allowedFields.contains(field))
                    .toList();
            if (!disallowed.isEmpty()) {
                return deny("Field(s) " + disallowed + " not permitted for resource '" + resource + "' under agent '"
                        + profile.agentId() + "'s ACAP profile — data minimization (never: fields.deny)");
            }
        }

        return Optional.empty();
    }

    private Optional<PolicyDecision> deny(String reason) {
        return Optional.of(PolicyDecision.deny(reason));
    }

    /**
     * Stage 6 (ADR-022): increments the matching threshold's usage counter
     * (every threshold whose {@code toolName} matches, regardless of {@code
     * currentOutcome} — an accurate usage count, not just a count of calls
     * that ended up ALLOWed) and, if a limit is now exceeded, escalates
     * ALLOW to HOLD. A call that's already HOLD/DENY is left alone — this
     * can only ever add a HOLD on top of a plain ALLOW.
     *
     * @return an escalating HOLD, or {@link Optional#empty()} to leave the caller's existing decision untouched
     */
    public Optional<PolicyDecision> checkThresholds(AcapProfile profile, String toolName,
                                                      PolicyDecision.Outcome currentOutcome) {
        for (AcapThreshold threshold : profile.thresholds()) {
            if (!toolName.equals(threshold.toolName())) {
                continue;
            }
            int count = thresholdTracker.incrementAndGet(profile.agentId(), threshold.metric());
            boolean exceeded = count > threshold.limit();
            boolean holdsOnExceed = "hold".equalsIgnoreCase(threshold.onExceed());
            if (exceeded && holdsOnExceed && currentOutcome == PolicyDecision.Outcome.ALLOW) {
                return Optional.of(PolicyDecision.hold("Threshold '" + threshold.metric() + "' exceeded (" + count
                        + " > " + threshold.limit() + ") for agent '" + profile.agentId() + "' — routed to a human"));
            }
        }
        return Optional.empty();
    }
}
