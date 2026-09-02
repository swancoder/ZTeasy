package com.zte.gateway.mcp.mask;

import com.zte.gateway.mcp.model.JsonRpcResponse;

/**
 * Interception point for masking PII in backend tool results before they are
 * relayed to the client over SSE.
 *
 * <p>Applied to every {@link JsonRpcResponse} returned by the backend MCP server —
 * never to denial responses, which never contain backend data (see
 * {@code McpProxyHandler#process}).
 */
public interface DataMaskingFilter {

    /**
     * @param agentId  the calling agent — masking is per-agent scope (ADR-032)
     * @param toolName the tool whose result this is (drives resource mapping)
     */
    JsonRpcResponse mask(String agentId, String toolName, JsonRpcResponse response);

    /**
     * Mask against the first profile matching {@code acapKeys} (ADR-039). An agent
     * has one key; a person is username-then-roles, and looking such a caller up by
     * a single id would quietly return an unmasked response.
     */
    default JsonRpcResponse mask(java.util.List<String> acapKeys, String toolName, JsonRpcResponse response) {
        return mask(acapKeys == null || acapKeys.isEmpty() ? null : acapKeys.get(0), toolName, response);
    }
}
