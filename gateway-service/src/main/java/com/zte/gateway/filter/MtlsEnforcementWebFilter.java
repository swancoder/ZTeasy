package com.zte.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * ADR-018 "Smart mTLS": enforces client-certificate <em>presence</em> on
 * MCP/agent and general S2S-facing paths, even though {@code server.ssl}
 * itself is configured {@code client-auth: want} (the TLS handshake succeeds
 * with or without a cert — see {@code application.yml}).
 *
 * <p>Deliberately a {@link WebFilter}, not a change to {@code server.ssl}
 * (which is transport-layer and can't express "required on these paths, not
 * those"), and deliberately not a second HTTPS listener (rejected in
 * ADR-018 — see its Alternatives Considered): the gateway's Admin UI
 * ({@code /admin/**}), Admin API ({@code /api/v1/admin/**}), internal
 * endpoints ({@code /api/v1/internal/**}), and actuator all keep working
 * over plain browser HTTPS, cert-free, on the exact same port 8080.
 *
 * <p>Protected: {@code /sse}, {@code /message} (the MCP proxy — see
 * {@code McpRouterConfig}), and {@code /api/v1/**} generally (the REST
 * proxy / S2S surface), excluding {@code /api/v1/admin/} and
 * {@code /api/v1/internal/} — the same two prefixes
 * {@code AuditExclusionProperties}/{@code application.yml}'s
 * {@code zte.audit.excluded-path-prefixes} already treats as non-proxied,
 * gateway-local traffic. Hardcoded here rather than externalized via
 * {@code @ConfigurationProperties} (unlike {@link AuditExclusionProperties}):
 * this is a fixed architectural decision (ADR-018), not an operator-tunable
 * list.
 *
 * <p><strong>Order:</strong> {@code HIGHEST_PRECEDENCE + 50} — runs before
 * Spring Security's {@code WebFilterChainProxy} (JWT bearer check), before
 * {@code AdminAuthorizationFilter} (implicit lowest precedence), before
 * {@code RequestAuditFilter} ({@code LOWEST_PRECEDENCE - 100}), and,
 * transitively, before every Gateway {@code GlobalFilter}
 * ({@code ZteAuthorizationFilter}/{@code ServiceToServiceAuthorizationFilter}/
 * {@code UserContextPropagationFilter}, all in the {@code HIGHEST_PRECEDENCE +
 * 100..200} range) — those only ever fire once {@code FilteringWebHandler} is
 * reached, downstream of every plain {@link WebFilter}. A missing client cert
 * on a protected path is therefore rejected before any JWT/policy work
 * happens, not layered after it.
 *
 * <p>A protected {@code /sse}/{@code /message} call still separately needs a
 * valid JWT bearer token — this filter only proves transport-layer identity;
 * it does not replace the existing {@code SecurityWebFilterChain}.
 */
@Component
public class MtlsEnforcementWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MtlsEnforcementWebFilter.class);

    private static final byte[] UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized\",\"message\":\"Client certificate required for this endpoint\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final boolean mtlsEnabled;

    public MtlsEnforcementWebFilter(@Value("${zte.mtls.enabled:true}") boolean mtlsEnabled) {
        this.mtlsEnabled = mtlsEnabled;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // zte.mtls.enabled=false (IT profile, application-it.yml) — server.ssl itself is
        // also disabled there, so there's no SslInfo to check regardless; this mirrors
        // MtlsHttpClientConfig's own @ConditionalOnProperty guard for the outbound side.
        if (!mtlsEnabled) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!isProtected(path)) {
            return chain.filter(exchange);
        }

        SslInfo sslInfo = exchange.getRequest().getSslInfo();
        if (sslInfo == null || sslInfo.getPeerCertificates() == null || sslInfo.getPeerCertificates().length == 0) {
            log.warn("ZT-MTLS-DENY path={} — no client certificate presented", path);
            return writeUnauthorized(exchange);
        }

        return chain.filter(exchange);
    }

    /**
     * {@code /sse}/{@code /message} (the MCP proxy) are always protected;
     * {@code /api/v1/**} is protected except the two gateway-local prefixes
     * that must stay reachable from a plain browser session.
     */
    private boolean isProtected(String path) {
        if (path.startsWith("/sse") || path.startsWith("/message")) {
            return true;
        }
        // /api/v1/approver/ (ADR-026): browser traffic from the Approval Center
        // SPA — cert-free for the same reason /api/v1/admin/ is.
        if (path.startsWith("/api/v1/admin/") || path.startsWith("/api/v1/internal/")
                || path.startsWith("/api/v1/approver/")) {
            return false;
        }
        return path.startsWith("/api/v1/");
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(UNAUTHORIZED_BODY);
        return response.writeWith(Mono.just(buffer));
    }
}
