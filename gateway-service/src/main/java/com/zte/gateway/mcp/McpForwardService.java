package com.zte.gateway.mcp;

import com.zte.gateway.mcp.mask.DataMaskingFilter;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Forwards an already-decided (ALLOW, or approved-after-HOLD) tool call to the
 * backend MCP server and masks the response — the one piece of "actually run
 * the tool" logic shared by {@link McpProxyHandler}'s ALLOW branch and {@code
 * com.zte.gateway.mcp.approval.PendingApprovalService}'s approve path (Stage
 * 1, ADR-019), so the masking rule never has to be applied in two places.
 */
@Component
public class McpForwardService {

    private final McpBackendClient backendClient;
    private final DataMaskingFilter dataMaskingFilter;

    public McpForwardService(McpBackendClient backendClient, DataMaskingFilter dataMaskingFilter) {
        this.backendClient = backendClient;
        this.dataMaskingFilter = dataMaskingFilter;
    }

    /**
     * ADR-039: masking needs the same profile lookup the decision used, which for a
     * person is username-then-roles rather than a single id.
     */
    public Mono<JsonRpcResponse> execute(java.util.List<String> acapKeys, JsonRpcRequest rpc) {
        return backendClient.forward(rpc)
                .map(response -> dataMaskingFilter.mask(acapKeys, rpc.toolName(), response));
    }

    public Mono<JsonRpcResponse> execute(String agentId, JsonRpcRequest rpc) {
        return backendClient.forward(rpc)
                .map(response -> dataMaskingFilter.mask(agentId, rpc.toolName(), response));
    }
}
