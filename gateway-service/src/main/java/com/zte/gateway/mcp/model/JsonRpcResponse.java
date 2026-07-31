package com.zte.gateway.mcp.model;

import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 response injected into a client's SSE stream.
 *
 * <p>Policy denials use {@link #denied} — a normal (non-error) JSON-RPC envelope
 * whose {@code result.isError} is {@code true}, matching how MCP {@code tools/call}
 * reports tool-level failures. This is deliberate: a denial is the policy engine
 * doing its job correctly, not a transport failure, so it must not be modeled as a
 * {@link JsonRpcError}.
 */
public record JsonRpcResponse(String jsonrpc, Object id, Map<String, Object> result, JsonRpcError error) {

    public static JsonRpcResponse success(Object id, Map<String, Object> result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse denied(Object id, String reason) {
        return new JsonRpcResponse("2.0", id, Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", "Action denied by ZTeasy Security Policy: " + reason)),
                "isError", true
        ), null);
    }

    public static JsonRpcResponse backendError(Object id, String message) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(-32000, message));
    }

    /**
     * Dead-end stub response (see ADR-010): confirms the caller authenticated
     * successfully and names which client the gateway saw, without evaluating
     * policy or forwarding to any backend MCP server.
     */
    public static JsonRpcResponse stubbed(Object id, String clientId) {
        return success(id, Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", "ZTeasy gateway: authenticated as '" + clientId
                                + "'. This is a stub response — no backend MCP server was called.")),
                "isError", false
        ));
    }
}
