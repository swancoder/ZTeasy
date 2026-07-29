package com.zte.gateway.mcp.policy;

import java.util.Map;

/**
 * Decides whether an agent may invoke a given MCP tool.
 *
 * <p>Deliberately synchronous (no {@code Mono}/{@code Flux}): evaluation is
 * expected to be pure in-memory logic with no I/O, unlike {@code PolicyService}
 * (which is DB-backed and reactive). {@code McpProxyHandler} calls this inline —
 * wrapping a non-blocking synchronous call in reactive machinery would add
 * nothing.
 */
public interface McpPolicyEngine {

    PolicyDecision evaluate(String agentId, String toolName, Map<String, Object> arguments);
}
