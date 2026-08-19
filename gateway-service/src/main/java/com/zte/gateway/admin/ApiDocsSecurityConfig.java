package com.zte.gateway.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Allows unauthenticated access to springdoc's self-served OpenAPI paths
 * (Stage 25 / ADR-025): {@code /v3/api-docs} (the spec JSON, which the Admin
 * Console's Documentation tab fetches — see {@code Documentation.tsx}) and
 * {@code /swagger-ui/**}/{@code /swagger-ui.html} (springdoc's own bundled
 * standalone UI, kept reachable for parity even though the Admin Console
 * embeds the spec itself rather than linking out to it).
 *
 * <p><strong>Scope note</strong> (same shape as {@link AdminUiConfig}'s): this
 * covers only the doc/spec paths, which describe route <em>shapes</em>, not
 * data. It does not widen access to {@code /api/v1/admin/**} or any other API
 * path — those stay behind the default JWT-required chain from
 * {@code auth-library}'s {@code SecurityConfig}.
 *
 * <p>Same {@code @Order} pattern as {@link com.zte.gateway.internal.InternalSecurityConfig}
 * and {@link AdminUiConfig} — a negative order runs before the default
 * {@code anyExchange().authenticated()} chain.
 */
@Configuration
public class ApiDocsSecurityConfig {

    @Bean
    @Order(-90)
    public SecurityWebFilterChain apiDocsSecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
