package com.zte.gateway.mcp.model;

/**
 * JSON-RPC 2.0 protocol-level error object.
 *
 * <p>Reserved for transport/backend failures (e.g. the downstream MCP server is
 * unreachable). Policy denials are NOT modeled as {@link JsonRpcError} — see
 * {@link JsonRpcResponse#denied}.
 */
public record JsonRpcError(int code, String message) {}
