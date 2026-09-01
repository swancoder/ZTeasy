package com.zte.gateway.admin;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.gateway.identity.IdentitySources;
import com.zte.gateway.policy.activation.ActivePolicyEvaluator;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.RealmRoles;
import com.zte.gateway.policy.def.RequestTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Enforces the YAML {@code users2service} policy on {@code /api/v1/admin/**}
 * (ADR-012), reusing the exact same {@link PolicyMatcher}/{@link PolicyDefinitionStore}
 * evaluation {@code ZteAuthorizationFilter} uses for gateway-routed paths.
 *
 * <p><strong>Why this can't just be {@code ZteAuthorizationFilter}:</strong>
 * that class implements Spring Cloud Gateway's {@code GlobalFilter}, which
 * only runs for requests {@code RoutePredicateHandlerMapping} matches to a
 * configured {@code InventoryRouteDefinitionLocator} route — verified empirically while
 * building this feature (a USER-role JWT got {@code 200} from
 * {@code AdminPolicyController} because {@code ZteAuthorizationFilter} never
 * ran for it at all; only Spring Security's JWT-required check, itself a
 * plain {@link WebFilter}, applied). {@code /api/v1/admin/**} is a local
 * {@code @RestController}, not a Gateway route, so this is a genuine
 * {@link WebFilter} instead — those wrap the whole WebFlux dispatch (Spring
 * Security's chain is one too), so they apply uniformly regardless of how
 * the request ends up being handled.
 *
 * <p>No explicit {@code @Order}/{@link org.springframework.core.Ordered} —
 * defaults to lowest precedence, i.e. runs after Spring Security's
 * {@code WebFilterChainProxy} (which authenticates the JWT and populates the
 * reactive security context this filter reads).
 */
@Component
public class AdminAuthorizationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorizationFilter.class);

    private static final byte[] FORBIDDEN_BODY =
            "{\"error\":\"Forbidden\",\"message\":\"No policy grants access to this resource\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final PolicyDefinitionStore policyDefinitionStore;
    private final ActivePolicyEvaluator activeEvaluator;

    public AdminAuthorizationFilter(PolicyDefinitionStore policyDefinitionStore, ActivePolicyEvaluator activeEvaluator) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.activeEvaluator = activeEvaluator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // /api/v1/approver/** (ADR-026) and /api/v1/dashboard/** (ADR-029) are the
        // other gateway-local, non-routed
        // API surface needing users2service enforcement — same GlobalFilter
        // caveat as /api/v1/admin/** (see class Javadoc), same filter, its own
        // YAML rules (u2s-approver-api-*: USER or ADMIN, vs. admin's ADMIN-only).
        if (!path.startsWith("/api/v1/admin/") && !path.startsWith("/api/v1/approver/")
                && !path.startsWith("/api/v1/dashboard/")) {
            return chain.filter(exchange);
        }

        // Mirrors ZteAuthorizationFilter's proven pattern exactly (plain instanceof check
        // inside flatMap) rather than .cast(...).switchIfEmpty(...): switchIfEmpty can't
        // reliably detect "was the JWT missing" here because evaluate() returns Mono<Void>
        // — a *successful* Void completion looks identical to an *empty* one (no value
        // either way), so switchIfEmpty fired unconditionally and double-wrote the
        // response on every request, throwing on the second write. Caught by
        // AdminAuthorizationFilterTest before this ever shipped.
        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl())
                .flatMap(ctx -> {
                    Authentication auth = ctx.getAuthentication();
                    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
                        // SecurityConfig already requires a JWT for /api/v1/admin/**, so this
                        // shouldn't happen — fail closed defensively.
                        return writeForbidden(exchange);
                    }
                    return evaluate(exchange, chain, jwtAuth, path);
                });
    }

    private Mono<Void> evaluate(ServerWebExchange exchange, WebFilterChain chain,
                                 JwtAuthenticationToken jwtAuth, String path) {
        List<String> roles = RealmRoles.extract(jwtAuth);
        String method = exchange.getRequest().getMethod().name();
        String targetService = RequestTargetResolver.targetService(path);
        List<String> sources = IdentitySources.enrich(roles, jwtAuth);
        PolicyEvaluation eval = activeEvaluator.evaluate("users2service",
                policyDefinitionStore.current().users2service(), sources, targetService, path, method);

        return switch (eval.outcome()) {
            case ALLOWED -> {
                String ruleId = eval.matchedRule().id();
                log.debug("ZT-ALLOW (yaml rule={}) roles={} method={} path={}", ruleId, roles, method, path);
                ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, ruleId, "ALLOW");
                yield chain.filter(exchange);
            }
            case DENIED -> {
                String ruleId = eval.matchedRule().id();
                log.warn("ZT-DENY (yaml rule={}) roles={} method={} path={}", ruleId, roles, method, path);
                ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, ruleId, "DENY");
                yield writeForbidden(exchange);
            }
            case NO_MATCH -> {
                log.warn("ZT-DENY (no yaml rule) roles={} method={} path={}", roles, method, path);
                ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, null, "DENY");
                yield writeForbidden(exchange);
            }
        };
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(FORBIDDEN_BODY);
        return response.writeWith(Mono.just(buffer));
    }
}
