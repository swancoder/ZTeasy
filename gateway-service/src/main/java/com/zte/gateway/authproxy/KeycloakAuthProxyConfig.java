package com.zte.gateway.authproxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Reverse-proxies Keycloak under the gateway's own origin at {@code /auth/**}
 * (ADR-027) — for deployments where the gateway is the <em>only</em>
 * externally exposed surface and the browser still needs to reach Keycloak's
 * login pages for the two SPAs' OIDC redirects (ADR-012, ADR-026).
 *
 * <p>Off by default ({@code zte.auth-proxy.enabled=false}): local dev keeps
 * talking to Keycloak directly on {@code localhost:8180} with Keycloak's
 * default root path, exactly as before. When enabled, Keycloak itself must
 * serve under the same subpath ({@code KC_HTTP_RELATIVE_PATH=/auth}) so no
 * path rewriting happens here — the route forwards {@code /auth/**} verbatim
 * to {@code zte.auth-proxy.uri}, and Keycloak's {@code KC_HOSTNAME_URL} must
 * be set to the gateway's external {@code https://<host>[:port]/auth} so the
 * issuer in every token matches what this gateway validates
 * ({@code KEYCLOAK_ISSUER_URI}) regardless of whether the token was fetched
 * through the proxy (browser) or over the internal network (agents,
 * {@code KEYCLOAK_JWKS_URI}).
 *
 * <p>Security posture: {@code /auth/**} is permitAll by construction — it
 * <em>is</em> the login surface an unauthenticated browser must reach; every
 * sensitive Keycloak surface (admin console) stays password-protected by
 * Keycloak itself. {@code ZteAuthorizationFilter} passes anonymous
 * (non-JWT) exchanges through untouched, so no YAML rule is needed —
 * verified by that filter's step 2 (see its Javadoc). Excluded from the
 * request audit trail ({@code zte.audit.excluded-path-prefixes}) — login
 * page assets and OIDC handshakes are transport noise, same class as
 * {@code /admin/} statics.
 */
@Configuration
@ConditionalOnProperty(name = "zte.auth-proxy.enabled", havingValue = "true")
public class KeycloakAuthProxyConfig {

    @Bean
    public RouteLocator keycloakAuthProxyRoutes(RouteLocatorBuilder builder,
                                                @Value("${zte.auth-proxy.uri}") String keycloakUri) {
        return builder.routes()
                .route("keycloak-auth-proxy", r -> r.path("/auth/**").uri(keycloakUri))
                .build();
    }

    @Bean
    @Order(-88)
    public SecurityWebFilterChain authProxySecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/auth/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
