package com.zte.gateway.mcp.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Placeholder {@link McpPolicyEngine}: an in-memory deny-list keyed on tool name.
 *
 * <p>Demo-grade only — a real implementation would consult per-agent, per-tool
 * grants (analogous to {@code AccessPolicyRepository}) rather than a fixed set.
 * Denies any tool name that is bulk-export or destructive-shaped, which is
 * exactly the class of MCP tool call a zero-trust proxy exists to intercept
 * (e.g. an {@code export_all_data}-style tool on a connected data source).
 */
@Component
public class DummyMcpPolicyEngine implements McpPolicyEngine {

    private static final Set<String> DENYLIST = Set.of("export_all_data", "delete_all", "drop_table");

    @Override
    public PolicyDecision evaluate(String agentId, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            return PolicyDecision.deny("Missing tool name");
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (DENYLIST.contains(normalized) || normalized.contains("delete") || normalized.contains("drop")) {
            return PolicyDecision.deny(
                    "Tool '" + toolName + "' matches the ZTeasy dummy deny-list (bulk-export/destructive pattern)");
        }
        return PolicyDecision.allow();
    }
}
