package com.zte.gateway.mcp.model;

import java.util.Map;

/**
 * JSON-RPC 2.0 request as sent by an MCP client to {@code POST /message}.
 *
 * <p>For a {@code tools/call} request, {@code params} is shaped as
 * {@code {"name": "<tool>", "arguments": {...}}} per the MCP spec — see
 * {@link #toolName()} and {@link #toolArguments()}.
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {

    /** Tool name from {@code params.name}, or {@code null} if absent/not a tools/call. */
    public String toolName() {
        if (params == null) return null;
        Object name = params.get("name");
        return name instanceof String s ? s : null;
    }

    /** Tool arguments from {@code params.arguments}, or an empty map if absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toolArguments() {
        if (params == null) return Map.of();
        Object args = params.get("arguments");
        return args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
