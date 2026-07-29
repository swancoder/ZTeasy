package com.zte.gateway.mcp;

import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Non-blocking client forwarding an allowed {@link JsonRpcRequest} to the actual
 * downstream MCP server.
 *
 * <p>{@code mcp-backend.uri} points at whatever MCP server this gateway fronts
 * (e.g. a service like this repo's {@code hubspot-mcp} sibling project). Demo-grade
 * simplification: expects the backend to answer each {@code POST /message} with a
 * single JSON-RPC response body, which this gateway then relays as one SSE event —
 * MCP's HTTP+SSE transport also allows the backend to stream, which a fuller
 * implementation would relay incrementally instead of buffering to one response.
 */
@Component
public class McpBackendClient {

    private final WebClient webClient;

    public McpBackendClient(WebClient.Builder builder,
                             @Value("${mcp-backend.uri:http://localhost:9090}") String backendUri) {
        this.webClient = builder.baseUrl(backendUri).build();
    }

    public Mono<JsonRpcResponse> forward(JsonRpcRequest request) {
        return webClient.post()
                .uri("/message")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonRpcResponse.class)
                .onErrorResume(ex -> Mono.just(
                        JsonRpcResponse.backendError(request.id(), "Backend MCP server error: " + ex.getMessage())));
    }
}
