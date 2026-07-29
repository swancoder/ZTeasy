package com.zte.gateway.mcp.audit;

/**
 * Records an MCP audit event without blocking the calling (proxy request) thread.
 */
public interface McpAuditService {

    void record(McpAuditEvent event);
}
