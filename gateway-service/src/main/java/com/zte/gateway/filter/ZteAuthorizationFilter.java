package com.zte.gateway.filter;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.RealmRoles;
import com.zte.gateway.policy.def.RequestTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Zero Trust authorization filter: enforces users2service access policies on every
 * authenticated request before forwarding to a downstream service.
 *
 * <p>Evaluation logic (in order):
 * <ol>
 *   <li>No security context → pass through (public path already permitted by Spring Security).</li>
 *   <li>Authentication is not a {@link JwtAuthenticationToken} → pass through (anonymous/permit-all).</li>
 *   <li>Extract {@code realm_access.roles} from the JWT. If empty and the JWT's {@code azp}
 *       identifies a service principal (not {@code zte.policy.user-client-id}), this is
 *       service2service traffic, not users2service — pass through and let
 *       {@link ServiceToServiceAuthorizationFilter} decide instead.</li>
 *   <li>Consult the YAML {@code users2service} rules (ADR-011/ADR-012) via {@link PolicyMatcher}:
 *       an explicit DENY or ALLOW match short-circuits the decision (deny always wins).</li>
 *   <li>No YAML rule matches → deny. YAML is the sole source of truth for users2service
 *       as of ADR-012 — the DB-backed fallback ({@code PolicyService}/{@code access_policies},
 *       ADR-003) has been retired.</li>
 *   <li>Denied → write {@code 403 Forbidden} response body and complete without
 *       calling the chain, preventing routing to the downstream service.</li>
 * </ol>
 *
 * <p>Order {@code Ordered.HIGHEST_PRECEDENCE + 100} ensures this runs before routing,
 * before {@link ServiceToServiceAuthorizationFilter} (+150), and before {@link RequestAuditFilter}.
 */
@Component
public class ZteAuthorizationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ZteAuthorizationFilter.class);

    private static final byte[] FORBIDDEN_BODY =
            "{\"error\":\"Forbidden\",\"message\":\"No policy grants access to this resource\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final PolicyDefinitionStore policyDefinitionStore;
    private final PolicyMatcher policyMatcher;
    private final PolicyDefaultsProperties policyDefaults;

    public ZteAuthorizationFilter(PolicyDefinitionStore policyDefinitionStore,
                                   PolicyMatcher policyMatcher,
                                   PolicyDefaultsProperties policyDefaults) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.policyMatcher = policyMatcher;
        this.policyDefaults = policyDefaults;
    }

    @Override
    public int getOrder() {
        // Run very early — before NettyWriteResponseFilter (-1), before NettyRoutingFilter (MAX).
        // Highest precedence + small offset so other critical early filters still precede us.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl()) // no context → empty ctx → pass-through
                .flatMap(ctx -> {
                    Authentication auth = ctx.getAuthentication();
                    // Non-JWT auth (anonymous / permit-all) — Spring Security already decided
                    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(exchange);
                    }

                    List<String> roles = RealmRoles.extract(jwtAuth);
                    String path   = exchange.getRequest().getPath().value();
                    String method = exchange.getRequest().getMethod().name();

                    if (roles.isEmpty() && isServicePrincipal(jwtAuth)) {
                        // No realm roles + a non-user azp: this is service2service traffic,
                        // not a user request — ServiceToServiceAuthorizationFilter decides.
                        return chain.filter(exchange);
                    }

                    String targetService = RequestTargetResolver.targetService(path);
                    PolicyEvaluation yamlEval = policyMatcher.evaluate(
                            policyDefinitionStore.current().users2service(), roles, targetService, path, method);

                    return switch (yamlEval.outcome()) {
                        case DENIED -> {
                            String ruleId = yamlEval.matchedRule().id();
                            log.warn("ZT-DENY (yaml rule={}) roles={} method={} path={}", ruleId, roles, method, path);
                            ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, ruleId, "DENY");
                            yield writeForbidden(exchange);
                        }
                        case ALLOWED -> {
                            String ruleId = yamlEval.matchedRule().id();
                            log.debug("ZT-ALLOW (yaml rule={}) roles={} method={} path={}", ruleId, roles, method, path);
                            ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, ruleId, "ALLOW");
                            yield chain.filter(exchange);
                        }
                        case NO_MATCH -> {
                            log.warn("ZT-DENY (no yaml rule) roles={} method={} path={}", roles, method, path);
                            ZteAuditLogger.policyDecision("users2service", roles.toString(), targetService, null, "DENY");
                            yield writeForbidden(exchange);
                        }
                    };
                });
    }

    /** {@code true} when the JWT's {@code azp} identifies a service/agent client rather than the interactive user client. */
    private boolean isServicePrincipal(JwtAuthenticationToken jwtAuth) {
        String azp = jwtAuth.getToken().getClaimAsString("azp");
        return azp != null && !azp.equals(policyDefaults.getUserClientId());
    }

    /**
     * Writes a 403 JSON response and marks the exchange as already-routed so that
     * downstream routing filters ({@code NettyRoutingFilter}) skip forwarding.
     */
    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        // Mark the exchange as already routed — prevents NettyRoutingFilter from forwarding
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ALREADY_ROUTED_ATTR, true);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = response.bufferFactory().wrap(FORBIDDEN_BODY);
        return response.writeWith(Mono.just(buffer));
    }
}
