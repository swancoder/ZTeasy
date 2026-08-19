package com.zte.gateway.policy.def;

import java.util.List;

/**
 * The parsed, versioned contents of a ZTE YAML policy file — the single runtime
 * source of truth for users2service, service2service, and agent@mcp/tool-call
 * access decisions alike (see {@code ZteAuthorizationFilter}; ADR-012 retired
 * the earlier DB-backed fallback for users2service).
 *
 * <p>{@code schemaVersion} must be {@code 1} today — {@link PolicyValidator}
 * rejects anything else, so a future incompatible schema change fails loudly at
 * load time instead of being silently misinterpreted.
 */
public record PolicyDocument(
        Integer schemaVersion,
        List<PolicyRule> users2service,
        List<PolicyRule> service2service,
        List<PolicyRule> agentMcpToolCalls,
        List<PolicyRule> agentMcpToolHolds
) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * Convenience constructor for every call site written before {@code
     * agentMcpToolHolds} existed (Stage 1, ADR-019) — defaults it to empty
     * rather than requiring every test/caller to pass a fifth {@code List.of()}.
     */
    public PolicyDocument(Integer schemaVersion, List<PolicyRule> users2service, List<PolicyRule> service2service,
                           List<PolicyRule> agentMcpToolCalls) {
        this(schemaVersion, users2service, service2service, agentMcpToolCalls, List.of());
    }

    /** Empty document — used as the safe fallback shape when no rules exist yet. */
    public static PolicyDocument empty() {
        return new PolicyDocument(SUPPORTED_SCHEMA_VERSION, List.of(), List.of(), List.of(), List.of());
    }

    public List<PolicyRule> users2service() {
        return users2service == null ? List.of() : users2service;
    }

    public List<PolicyRule> service2service() {
        return service2service == null ? List.of() : service2service;
    }

    public List<PolicyRule> agentMcpToolCalls() {
        return agentMcpToolCalls == null ? List.of() : agentMcpToolCalls;
    }

    /**
     * Tool names that must be routed to a human for approval rather than
     * forwarded straight to the backend, even when {@code agentMcpToolCalls}
     * would otherwise ALLOW them (Stage 1 HOLD, ADR-019). Reuses {@link
     * PolicyRule}'s shape (id/source/target/priority) purely for YAML-authoring
     * and validation convenience — {@code effect} is conventionally {@code
     * ALLOW} on every entry here and is otherwise ignored: matching is via
     * {@link PolicyMatcher#matchAny}, not {@link PolicyMatcher#evaluate}'s
     * ALLOW/DENY precedence. {@code pathPattern}/{@code methods} are unused,
     * same as {@code agentMcpToolCalls}.
     */
    public List<PolicyRule> agentMcpToolHolds() {
        return agentMcpToolHolds == null ? List.of() : agentMcpToolHolds;
    }
}
