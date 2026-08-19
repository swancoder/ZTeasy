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

    /**
     * Stage 1 (ADR-019): the call was neither allowed nor denied — it's parked
     * pending a human decision at {@code approvalId} (see {@code
     * com.zte.gateway.mcp.approval}). A non-error, non-success envelope
     * ({@code isError} omitted rather than {@code true}): this is not a policy
     * failure, and a client that only checks {@code isError} shouldn't treat it
     * as one.
     */
    public static JsonRpcResponse held(Object id, String approvalId, String reason) {
        return new JsonRpcResponse("2.0", id, Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", "Action held for human approval: " + reason)),
                "status", "held",
                "approvalId", approvalId
        ), null);
    }

    public static JsonRpcResponse backendError(Object id, String message) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(-32000, message));
    }
}
