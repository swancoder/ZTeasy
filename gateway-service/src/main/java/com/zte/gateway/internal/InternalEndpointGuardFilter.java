package com.zte.gateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.security.MessageDigest;

/**
 * Requires a shared secret on {@code /api/v1/internal/**} whenever one is
 * configured (ADR-027 amendment).
 *
 * <p>{@link InternalSecurityConfig} leaves these endpoints unauthenticated on
 * the stated assumption that they are "protected at the network level (Docker
 * bridge; not proxied externally)". A public ingress voids that assumption:
 * verified live against the Azure deployment, an anonymous caller from the
 * internet got the full policy document from {@code GET
 * /api/v1/internal/policies} and could fire {@code POST .../reload}.
 *
 * <p><strong>Why a secret and not an IP check.</strong> The first attempt
 * classified callers by address (private/CGNAT = inside). It does not work on
 * this deployment: the gateway sits behind a <em>TCP passthrough</em> ingress,
 * so nothing terminates HTTP in front of it — no {@code X-Forwarded-For} is
 * added, and every request, internet or in-cluster, arrives from an internal
 * address. Measured, not assumed: with the IP guard deployed, the endpoint
 * still answered 200 from the public internet. A header secret is
 * transport-independent, so it holds for both topologies.
 *
 * <p>Configured via {@code zte.internal.api-key} (env
 * {@code ZTE_INTERNAL_API_KEY}). Blank — the default — keeps the historical
 * behavior for local development, where the endpoints really are only
 * reachable on the Docker bridge. Callers send it as
 * {@code X-ZTE-Internal-Key}; {@code zt-agents} does this automatically when
 * the same value is in its environment.
 *
 * <p>Order {@code HIGHEST_PRECEDENCE + 40} — before
 * {@code MtlsEnforcementWebFilter} (+50) and Spring Security, so a rejected
 * request costs nothing downstream.
 */
@Component
public class InternalEndpointGuardFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalEndpointGuardFilter.class);

    private static final String PREFIX = "/api/v1/internal/";
    static final String HEADER = "X-ZTE-Internal-Key";

    private static final byte[] FORBIDDEN_BODY =
            "{\"error\":\"Forbidden\",\"message\":\"Internal endpoints require a valid internal key\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final String apiKey;

    public InternalEndpointGuardFilter(@Value("${zte.internal.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 40;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (apiKey.isEmpty() || !exchange.getRequest().getPath().value().startsWith(PREFIX)) {
            return chain.filter(exchange);
        }

        String presented = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (presented != null && constantTimeEquals(presented, apiKey)) {
            return chain.filter(exchange);
        }

        log.warn("ZT-INTERNAL-DENY path={} — missing or wrong {}",
                exchange.getRequest().getPath().value(), HEADER);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(FORBIDDEN_BODY);
        return response.writeWith(Mono.just(buffer));
    }

    /** Length-independent comparison so a wrong key can't be probed by timing. */
    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
