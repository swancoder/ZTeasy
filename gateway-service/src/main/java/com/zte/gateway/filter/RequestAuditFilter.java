package com.zte.gateway.filter;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.gateway.audit.ClientIpResolver;
import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogAuditService;
import com.zte.gateway.inventory.HealthTelemetryService;
import com.zte.gateway.policy.def.RequestTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Injects trusted identity/tracing headers for downstream services, and logs
 * every <em>zero-trust-relevant</em> request (allowed or denied) — proxied
 * REST calls and MCP tool calls, not the Admin Console's own traffic or
 * health probes (ADR-013, amended: the original version audited literally
 * every request, including the Admin Console observing its own existence —
 * see {@link AuditExclusionProperties}).
 *
 * <p>Zero Trust principle: downstream services should NEVER trust client-supplied
 * identity headers. Only headers set by this gateway are authoritative.
 *
 * <p><strong>Plain {@link WebFilter}, not a Gateway {@code GlobalFilter}</strong>
 * (this class used to be one) — for the same reason {@code AdminAuthorizationFilter}
 * is (see its Javadoc, ADR-012): {@code GlobalFilter}s only run for requests
 * {@code RoutePredicateHandlerMapping} matches to a configured
 * {@code InventoryRouteDefinitionLocator} route, so as a {@code GlobalFilter} this never saw
 * {@code /api/v1/admin/**}/{@code /api/v1/internal/**} traffic, and never saw a
 * request denied by an earlier filter (which short-circuits without calling
 * {@code chain.filter()}). A {@link WebFilter} wraps the <em>entire</em>
 * downstream decision — for a Gateway-routed path that includes the whole
 * {@code GlobalFilter} chain internally — so {@link #filter}'s {@code doFinally}
 * observes the final status code regardless of which layer set it.
 *
 * <p>Order: after Spring Security's authentication (so the reactive security
 * context is populated) but before {@code AdminAuthorizationFilter}'s implicit
 * lowest-precedence default, so this filter's {@code chain.filter()} call is
 * what actually invokes it — otherwise this couldn't observe its decision.
 *
 * <p><strong>Known gap</strong> (see ADR-013 Self-Critique): a request with no
 * token at all is rejected by Spring Security's own filter before reaching
 * this one, so it is not captured here — every denial scenario tested in this
 * codebase uses a present-but-insufficient-role JWT (403), not a missing
 * token (401).
 */
@Component
public class RequestAuditFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestAuditFilter.class);

    /** Identifies which gateway JVM instance handled the request — distinct from
     * {@code traceId}, which travels across services. Computed once. */
    private static final String PROCESS_ID = String.valueOf(ProcessHandle.current().pid());

    private final RequestLogAuditService auditService;
    private final AuditExclusionProperties exclusions;
    private final HealthTelemetryService healthTelemetryService;

    public RequestAuditFilter(RequestLogAuditService auditService, AuditExclusionProperties exclusions,
                               HealthTelemetryService healthTelemetryService) {
        this.auditService = auditService;
        this.exclusions = exclusions;
        this.healthTelemetryService = healthTelemetryService;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId       = resolveTraceId(exchange);
        String clientIp      = resolveClientIp(exchange);
        String userAgent     = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
        String path          = exchange.getRequest().getPath().value();
        String httpMethod    = exchange.getRequest().getMethod().name();
        String targetService = RequestTargetResolver.targetService(path);

        // Captured inside the flatMap below (synchronously, before chain.filter() is
        // invoked) and read back in doFinally — a plain reference, not Reactor Context,
        // since doFinally's Consumer<SignalType> callback doesn't participate in context
        // propagation the way an operator further up the same chain would.
        AtomicReference<String> subjectRef = new AtomicReference<>();
        AtomicReference<String> displayIdentityRef = new AtomicReference<>();
        AtomicReference<String> initiatorClientRef = new AtomicReference<>();

        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl()) // no context → empty ctx → treated as anonymous
                .flatMap(ctx -> {
                    Authentication auth = ctx.getAuthentication();
                    String subject = null;
                    String displayIdentity = null;
                    String initiatorClient = null;
                    if (auth instanceof JwtAuthenticationToken jwtAuth) {
                        subject = jwtAuth.getToken().getSubject();
                        // preferred_username is human-readable ("zte-admin",
                        // "service-account-agent-b") vs. sub's opaque Keycloak UUID — used
                        // ONLY for the audit trail's display value (originalUserObo below),
                        // never for X-User-Id or the OBO token itself (UserContextPropagationFilter
                        // reads sub independently, straight from the security context — this
                        // filter's own display choice can't affect what's cryptographically
                        // propagated downstream).
                        String preferredUsername = jwtAuth.getToken().getClaimAsString("preferred_username");
                        displayIdentity = preferredUsername != null ? preferredUsername : subject;
                        initiatorClient = jwtAuth.getToken().getClaimAsString("azp");
                        log.info("ZT-AUDIT sub={} azp={} path={} traceId={}", subject, initiatorClient, path, traceId);
                    }
                    subjectRef.set(subject);
                    displayIdentityRef.set(displayIdentity);
                    initiatorClientRef.set(initiatorClient);

                    String finalSubject = subject;
                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r
                                    .headers(h -> {
                                        h.remove("X-User-Id");   // strip untrusted
                                        if (finalSubject != null) {
                                            h.add("X-User-Id", finalSubject);
                                        }
                                        h.remove("X-Request-Id");
                                        h.add("X-Request-Id", traceId); // guarantee forwarded downstream
                                    }))
                            .build();
                    return chain.filter(mutated);
                })
                .doFinally(signal -> {
                    Integer statusCode = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : null;

                    // Inventory health telemetry (ADR-016) — deliberately NOT gated by
                    // isAuditScoped: it's a different concern (APIM registry freshness,
                    // not the request_logs audit trail) with its own no-op-if-unregistered
                    // safety net (HealthTelemetryService/upsertSuccessfulCallByServiceName),
                    // so it doesn't need — or want — the same exclusion list.
                    if (statusCode != null && statusCode >= 200 && statusCode < 300) {
                        healthTelemetryService.recordSuccessfulCall(targetService);
                    }

                    if (!isAuditScoped(path)) {
                        return;
                    }
                    ZteAuditLogger.requestLog(traceId, path, statusCode);
                    auditService.record(RequestLog.forRest(
                            traceId, clientIp, userAgent, PROCESS_ID, path, statusCode, null,
                            initiatorClientRef.get(), displayIdentityRef.get(), targetService, httpMethod,
                            decisionEffect(statusCode)));
                });
    }

    /**
     * A coarse, status-code-derived signal, not per-policy-rule provenance
     * (ADR-017 Self-Criticism) — this filter observes the final response
     * after the whole {@code GlobalFilter} chain (and possibly the
     * downstream service itself) has run, so it cannot distinguish "ZTE's
     * own policy layer denied this" from "the downstream service returned
     * this status on its own."
     */
    private static String decisionEffect(Integer statusCode) {
        if (statusCode == null) {
            return null;
        }
        if (statusCode >= 200 && statusCode < 300) {
            return "ALLOW";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "DENY";
        }
        return "ERROR";
    }

    /**
     * {@code false} for paths under {@code zte.audit.excluded-path-prefixes}
     * (Admin Console self-traffic, internal endpoints, health checks) — an
     * exclude-list rather than enumerating proxied routes individually, so a
     * future route added to {@code InventoryRouteDefinitionLocator} is audited automatically.
     */
    private boolean isAuditScoped(String path) {
        return exclusions.getExcludedPathPrefixes().stream().noneMatch(path::startsWith);
    }

    /** Uses the caller-supplied {@code X-Request-Id} if present, otherwise mints a new one. */
    private String resolveTraceId(ServerWebExchange exchange) {
        String existing = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        return ClientIpResolver.resolve(forwarded, exchange.getRequest().getRemoteAddress());
    }
}
