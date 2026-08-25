package com.zte.gateway.approver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Serves the standalone Approval Center SPA (ADR-026) at {@code /approver/**},
 * unauthenticated — the SPA handles its own Keycloak login redirect, exactly
 * mirroring {@code AdminUiConfig}'s pattern for {@code /admin/**} (ADR-012).
 *
 * <p>Also permits {@code /ui-config.js} ({@link UiConfigController}) — the
 * runtime OIDC-authority snippet both SPAs load before their bundle. It must
 * be readable pre-login by construction (it tells the SPA <em>where</em> to
 * log in) and carries no secrets, only a public URL.
 *
 * <p><strong>Critical scope note</strong> (same as {@code AdminUiConfig}'s):
 * this permitAll covers only the static bundle and the config snippet — not
 * {@code /api/v1/approver/**} (the JSON API), which stays behind the default
 * JWT-required chain plus the {@code u2s-approver-api-*} YAML rules enforced
 * by {@code AdminAuthorizationFilter}.
 */
@Configuration
public class ApproverUiConfig implements WebFluxConfigurer {

    @Bean
    @Order(-89)
    public SecurityWebFilterChain approverUiSecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/approver/**", "/ui-config.js"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/approver/**")
                .addResourceLocations("classpath:/static/approver/")
                .resourceChain(true);
    }
}
