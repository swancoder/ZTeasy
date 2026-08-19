package com.zte.gateway.policy.def;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure, in-memory rule matching — no I/O, safe to call from the reactive gateway
 * path or synchronously from {@code McpPolicyEngine.evaluate()} (ADR-009; see also SPECS.md §5.4).
 *
 * <p>Precedence: among all rules matching the request tuple, an explicit DENY
 * always wins over an explicit ALLOW, regardless of declaration order or
 * priority — priority only breaks ties <em>within</em> the same effect. No rule
 * matching yields {@link PolicyEvaluation.Outcome#NO_MATCH}; it is the caller's
 * job to resolve that against a fallback (DB policy, or a configured default
 * effect).
 */
@Component
public class PolicyMatcher {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * @param rules   candidate rules (one category from a {@link PolicyDocument})
     * @param sources caller identities to match against {@code rule.source()} — e.g. all of a
     *                user's roles, or the single calling service/agent id
     * @param target  resource being accessed — service name or MCP tool name
     * @param path    request path, or {@code null} when not path-scoped (MCP tool calls)
     * @param method  HTTP method, or {@code null} when not method-scoped (MCP tool calls)
     */
    public PolicyEvaluation evaluate(List<PolicyRule> rules, List<String> sources, String target,
                                      String path, String method) {
        return evaluate(rules, sources, target, path, method, null);
    }

    /**
     * As {@link #evaluate(List, List, String, String, String)}, additionally
     * requiring a rule's {@code mcpTarget} to match {@code mcpIdentifier} when
     * the rule specifies one (ADR-023) — the REST authorization filters call
     * the 5-arg overload above with no MCP identifier to check; {@code
     * YamlMcpPolicyEngine} is the only caller of this one, passing the
     * configured {@code mcp-backend.name}.
     *
     * @param mcpIdentifier the calling backend's identifier ({@code mcp-backend.name}), or {@code null} outside the MCP category
     */
    public PolicyEvaluation evaluate(List<PolicyRule> rules, List<String> sources, String target,
                                      String path, String method, String mcpIdentifier) {
        List<PolicyRule> matches = rules.stream()
                .filter(rule -> sources.stream().anyMatch(source -> PATH_MATCHER.match(rule.source(), source)))
                .filter(rule -> PATH_MATCHER.match(rule.target(), target))
                .filter(rule -> pathMatches(rule, path))
                .filter(rule -> methodMatches(rule, method))
                .filter(rule -> mcpTargetMatches(rule, mcpIdentifier))
                .toList();

        Optional<PolicyRule> deny = highestPriority(matches, RuleEffect.DENY);
        if (deny.isPresent()) return PolicyEvaluation.denied(deny.get());

        Optional<PolicyRule> allow = highestPriority(matches, RuleEffect.ALLOW);
        if (allow.isPresent()) return PolicyEvaluation.allowed(allow.get());

        return PolicyEvaluation.noMatch();
    }

    /**
     * Highest-priority rule (any effect, ties broken by priority alone) whose
     * source/target/mcpTarget match — for callers that don't want ALLOW/DENY
     * precedence, just "does some rule apply." Used by {@code YamlMcpPolicyEngine}
     * against {@code agentMcpToolHolds} (Stage 1 HOLD, ADR-019): a separate rule
     * list, deliberately not folded into {@code agentMcpToolCalls}'s ALLOW/DENY
     * {@link #evaluate} — HOLD is an MCP-only concept with no meaning for
     * {@code users2service}/{@code service2service} REST traffic, so it isn't a
     * third {@link RuleEffect} value shared by every category's evaluation
     * (which would force every REST authorization filter's exhaustive switch to
     * grow a meaningless case).
     */
    public Optional<PolicyRule> matchAny(List<PolicyRule> rules, List<String> sources, String target) {
        return matchAny(rules, sources, target, null);
    }

    /** As {@link #matchAny(List, List, String)}, additionally requiring {@code mcpTarget} to match when a rule specifies one (ADR-023). */
    public Optional<PolicyRule> matchAny(List<PolicyRule> rules, List<String> sources, String target,
                                          String mcpIdentifier) {
        return rules.stream()
                .filter(rule -> sources.stream().anyMatch(source -> PATH_MATCHER.match(rule.source(), source)))
                .filter(rule -> PATH_MATCHER.match(rule.target(), target))
                .filter(rule -> mcpTargetMatches(rule, mcpIdentifier))
                .max(Comparator.comparingInt(PolicyRule::priority));
    }

    private Optional<PolicyRule> highestPriority(List<PolicyRule> matches, RuleEffect effect) {
        return matches.stream()
                .filter(r -> r.effect() == effect)
                .max(Comparator.comparingInt(PolicyRule::priority));
    }

    private boolean pathMatches(PolicyRule rule, String path) {
        if (rule.pathPattern() == null) return true;
        return path != null && PATH_MATCHER.match(rule.pathPattern(), path);
    }

    private boolean methodMatches(PolicyRule rule, String method) {
        if (rule.methods() == null || "*".equals(rule.methods())) return true;
        if (method == null) return false;
        return Arrays.asList(rule.methods().split(",")).contains(method);
    }

    /**
     * A rule with no {@code mcpTarget} matches regardless of backend (ADR-023) —
     * same "unscoped means universal" convention {@link #pathMatches}/{@link
     * #methodMatches} already use for {@code pathPattern}/{@code methods}. A
     * rule that does specify one only matches when it equals {@code
     * mcpIdentifier} exactly (not an Ant pattern — backend identifiers are
     * exact config values, not hierarchical paths).
     */
    private boolean mcpTargetMatches(PolicyRule rule, String mcpIdentifier) {
        if (rule.mcpTarget() == null) return true;
        return rule.mcpTarget().equals(mcpIdentifier);
    }
}
