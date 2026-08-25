package com.zte.gateway.authproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Blocks Keycloak's administrative surface from being reachable through the
 * gateway's public {@code /auth} reverse proxy (ADR-027 amendment).
 *
 * <p>The proxy exists so browsers can complete the OIDC redirect for the two
 * SPAs — that needs the realm's login endpoints and its static resources,
 * nothing else. Proxying {@code /auth/**} wholesale also published Keycloak's
 * admin console and the {@code master} realm's login on the public origin
 * (verified live: {@code GET /auth/admin/master/console/} returned 200),
 * where the demo's admin credentials are the only thing standing between the
 * internet and full IdP control. Refused here instead:
 *
 * <ul>
 *   <li>{@code /auth/admin/**} — admin console and Admin REST API</li>
 *   <li>{@code /auth/realms/master/**} — the master realm (its login page is
 *       the admin console's front door); the product realm keeps working</li>
 *   <li>{@code /auth/metrics}, {@code /auth/health/**} — operational endpoints</li>
 * </ul>
 *
 * <p>In-cluster callers reach Keycloak directly ({@code http://keycloak:8080}),
 * so nothing legitimate loses access: this filter only sits on the gateway's
 * own public path. Bound to the same {@code zte.auth-proxy.enabled} flag as
 * the proxy itself — with no proxy there is no surface to guard.
 *
 * <p>Order {@code HIGHEST_PRECEDENCE + 45}: after the internal-endpoint guard,
 * before {@code MtlsEnforcementWebFilter} (+50) and Spring Security, so a
 * blocked path never reaches routing.
 */
@Component
@ConditionalOnProperty(name = "zte.auth-proxy.enabled", havingValue = "true")
public class KeycloakAdminSurfaceGuardFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminSurfaceGuardFilter.class);

    private static final byte[] NOT_FOUND_BODY =
            "{\"error\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 45;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isAdminSurface(path)) {
            return chain.filter(exchange);
        }

        log.warn("ZT-AUTHPROXY-DENY path={} — Keycloak admin surface is not exposed through the public proxy", path);
        ServerHttpResponse response = exchange.getResponse();
        // 404 rather than 403: an admin console that isn't published shouldn't
        // advertise that it exists behind this origin.
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(NOT_FOUND_BODY);
        return response.writeWith(Mono.just(buffer));
    }

    static boolean isAdminSurface(String path) {
        return path.startsWith("/auth/admin/")
                || path.equals("/auth/admin")
                || path.startsWith("/auth/realms/master/")
                || path.equals("/auth/realms/master")
                || path.startsWith("/auth/metrics")
                || path.startsWith("/auth/health");
    }
}
