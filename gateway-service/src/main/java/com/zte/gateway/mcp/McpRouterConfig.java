package com.zte.gateway.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes for the MCP proxy endpoints.
 *
 * <p>Deliberately plain WebFlux {@link RouterFunction}s, not Spring Cloud Gateway
 * routes: unlike {@link com.zte.gateway.inventory.InventoryRouteDefinitionLocator}'s
 * dynamic routes (ADR-017), which proxy one request to one response, {@code POST
 * /message} must inject its result into a *different*, already-open connection
 * ({@code GET /sse}) — see {@link McpSessionManager}. Gateway's routing model has
 * no way to express that.
 *
 * <p>{@code /sse} and {@code /message} don't overlap with
 * {@code /api/v1/service-a|b/**}, so this coexists with Gateway routing without
 * conflict. Both inherit the same default-deny {@code SecurityConfig}
 * ({@code anyExchange().authenticated()}) — no security config changes needed.
 */
@Configuration
public class McpRouterConfig {

    @Bean
    public RouterFunction<ServerResponse> mcpRoutes(McpProxyHandler handler) {
        return RouterFunctions.route()
                .GET("/sse", handler::handleSse)
                .POST("/message", handler::handleMessage)
                .build();
    }
}
