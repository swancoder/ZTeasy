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
}
