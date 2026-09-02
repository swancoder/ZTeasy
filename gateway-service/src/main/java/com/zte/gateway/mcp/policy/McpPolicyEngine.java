package com.zte.gateway.mcp.policy;

import java.util.Map;

/**
 * Decides whether an agent may invoke a given MCP tool.
 *
 * <p>Deliberately synchronous (no {@code Mono}/{@code Flux}): evaluation is
 * expected to be pure in-memory logic with no I/O — it reads
 * {@code PolicyDefinitionStore}'s in-memory snapshot, never fetches inline.
 * {@code McpProxyHandler} calls this inline — wrapping a non-blocking synchronous
 * call in reactive machinery would add nothing.
 */
public interface McpPolicyEngine {

    PolicyDecision evaluate(String agentId, String toolName, Map<String, Object> arguments);

    /**
     * The same decision for a caller that may be a person rather than an agent
     * (ADR-039). The agent form above delegates here, so there is one decision
     * path regardless of who is calling.
     */
    default PolicyDecision evaluate(McpCaller caller, String toolName, Map<String, Object> arguments) {
        return evaluate(caller.id(), toolName, arguments);
    }
}
