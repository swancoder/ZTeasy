package com.zte.gateway.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Serves the React Admin Console (ADR-012) static bundle at {@code /admin/**},
 * unauthenticated — the SPA handles its own Keycloak login redirect.
 *
 * <p><strong>Critical scope note:</strong> this permitAll matcher covers only
 * the bare {@code /admin/**} path (HTML/JS/CSS, packaged from
 * {@code zt-admin-ui/dist} into {@code classpath:/static/admin/} at build
 * time — see {@code gateway-service/build.gradle.kts}'s {@code buildAdminUi}
 * task). It does <strong>not</strong> cover {@code /api/v1/admin/**} (the
 * JSON API, a different path prefix) — that stays behind the default
 * JWT-required chain from {@code auth-library}'s {@code SecurityConfig} plus
 * the ADMIN-only YAML {@code users2service} rule enforced by
 * {@code ZteAuthorizationFilter}. Widening this matcher would be a real
 * authorization bypass; keep the two prefixes distinct.
 *
 * <p>Same {@code @Order} pattern as {@link com.zte.gateway.internal.InternalSecurityConfig}
 * — a negative order runs before {@code auth-library}'s default
 * {@code anyExchange().authenticated()} chain.
 */
@Configuration
public class AdminUiConfig implements WebFluxConfigurer {

    @Bean
    @Order(-90)
    public SecurityWebFilterChain adminUiSecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/admin/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }

    /**
     * Serves the bundle's files (including {@code index.html} itself) at their
     * matching {@code /admin/**} URL. The SPA's OIDC {@code redirect_uri} points
     * at the exact file {@code /admin/index.html} (not a bare directory path),
     * so no directory-index-fallback resolver is needed.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .resourceChain(true);
    }
}
