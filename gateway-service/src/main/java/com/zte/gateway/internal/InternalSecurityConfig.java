package com.zte.gateway.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Higher-priority security chain that allows unauthenticated access to
 * {@code /api/v1/internal/**} endpoints.
 *
 * <p>Spring Security evaluates {@link SecurityWebFilterChain} beans in {@link Order}.
 * With {@code @Order(-100)}, this chain is checked before the default chain from
 * {@code auth-library/SecurityConfig} (which requires JWT for all other paths).
 *
 * <p><strong>Why no JWT?</strong> The gateway runs on HTTP (port 8080), making
 * transport-layer mTLS infeasible without a second HTTPS listener. The internal
 * endpoint is protected at the network level (Docker bridge; not proxied externally
 * via {@code GatewayRouteConfig}).
 *
 * <p>The {@link ZteAuthorizationFilter} passes unauthenticated requests through
 * automatically (its {@code defaultIfEmpty} path skips the DB policy check when
 * no {@code JwtAuthenticationToken} is present in the security context).
 *
 * <p><strong>Production upgrade:</strong> remove this class, add a Keycloak service
 * account for {@code zt-agents} with an {@code INTERNAL} role, and insert a DB
 * policy row granting {@code INTERNAL → /api/v1/internal/**}.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    @Order(-100)
    public SecurityWebFilterChain internalApiSecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/internal/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
