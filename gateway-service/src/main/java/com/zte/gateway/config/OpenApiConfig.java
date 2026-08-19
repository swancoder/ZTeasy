package com.zte.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the gateway's own API (Stage 25 / ADR-025) — springdoc
 * auto-discovers every {@code @RestController} under {@code com.zte.gateway}
 * from this, no per-endpoint annotation required. This bean only supplies the
 * document-level {@link Info} block; route/parameter shapes come straight
 * from the existing controllers' Spring MVC annotations.
 *
 * <p>Scope: covers {@code /api/v1/admin/**} (Admin Console backend) and
 * {@code /api/v1/internal/**} (zt-agents/ops tooling). Does not cover the
 * Spring Cloud Gateway proxy routes to service-a/service-b (pass-through,
 * not gateway-owned endpoints), the MCP proxy's {@code RouterFunction}
 * ({@code GET /sse}, {@code POST /message} — functional endpoints springdoc
 * can't richly document without {@code @RouterOperation} annotations, not
 * added here since the JSON-RPC-over-SSE shape doesn't fit REST-style docs
 * anyway), or the downstream MCP backend's own tool surface (see the Admin
 * Console's Registry tab for that, captured via APIM auto-discovery).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ZTE Gateway API")
                        .description("Admin and internal API surface exposed by gateway-service. "
                                + "/api/v1/admin/** requires a Keycloak JWT with the ADMIN role "
                                + "(the Admin Console's own backend); /api/v1/internal/** is "
                                + "unauthenticated, network-perimeter-only tooling (zt-agents, ops).")
                        .version("v1"));
    }
}
