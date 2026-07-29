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

    JsonRpcResponse mask(JsonRpcResponse response);
}
