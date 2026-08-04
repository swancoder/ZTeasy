package com.zte.gateway.policy.def;

import java.util.List;

/**
 * The parsed, versioned contents of a ZTE YAML policy file — the single runtime
 * source of truth for service2service and agent@mcp/tool-call access decisions,
 * and an additive, deny/allow-first layer in front of the DB-backed
 * {@code PolicyService} for users2service (see {@code ZteAuthorizationFilter}).
 *
 * <p>{@code schemaVersion} must be {@code 1} today — {@link PolicyValidator}
 * rejects anything else, so a future incompatible schema change fails loudly at
 * load time instead of being silently misinterpreted.
 */
public record PolicyDocument(
        Integer schemaVersion,
        List<PolicyRule> users2service,
        List<PolicyRule> service2service,
        List<PolicyRule> agentMcpToolCalls
) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Empty document — used as the safe fallback shape when no rules exist yet. */
    public static PolicyDocument empty() {
        return new PolicyDocument(SUPPORTED_SCHEMA_VERSION, List.of(), List.of(), List.of());
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
}
