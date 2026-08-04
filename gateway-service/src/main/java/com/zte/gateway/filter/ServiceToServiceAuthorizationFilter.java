package com.zte.gateway.filter;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyEvaluation;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.RequestTargetResolver;
import com.zte.gateway.policy.def.RuleEffect;
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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Zero Trust authorization filter for <b>service2service</b> traffic (ADR-011):
 * requests authenticated with a service/agent OAuth2 client-credentials JWT
 * (an {@code azp} other than {@code zte.policy.user-client-id}) rather than an
 * interactive human user.
 *
 * <p>{@link ZteAuthorizationFilter} already passes such requests straight
 * through (it governs role-bearing user JWTs, not service principals) — this
 * filter is where they are actually decided, against the YAML
 * {@code service2service} rules. Order {@code HIGHEST_PRECEDENCE + 150}: after
 * {@link ZteAuthorizationFilter} (+100), before {@link UserContextPropagationFilter}
 * (+200), so no OBO token is minted for a request this filter is about to deny.
 *
 * <p>Requests from an interactive user (no {@code azp}, or {@code azp} equal to
 * the user client) are not this filter's concern and pass through unaffected.
 * Unlike users2service, there is no DB fallback here — YAML is the sole source
 * of truth for service2service, and "no matching rule" resolves to the
 * configured default effect (deny-by-default).
 */
@Component
public class ServiceToServiceAuthorizationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ServiceToServiceAuthorizationFilter.class);

    private static final byte[] FORBIDDEN_BODY =
            "{\"error\":\"Forbidden\",\"message\":\"No service2service policy grants access to this resource\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final PolicyDefinitionStore policyDefinitionStore;
    private final PolicyMatcher policyMatcher;
    private final PolicyDefaultsProperties policyDefaults;

    public ServiceToServiceAuthorizationFilter(PolicyDefinitionStore policyDefinitionStore,
                                                PolicyMatcher policyMatcher,
                                                PolicyDefaultsProperties policyDefaults) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.policyMatcher = policyMatcher;
        this.policyDefaults = policyDefaults;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl())
                .flatMap(ctx -> {
                    Authentication auth = ctx.getAuthentication();
                    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(exchange);
                    }

                    String callerService = jwtAuth.getToken().getClaimAsString("azp");
                    if (callerService == null || callerService.equals(policyDefaults.getUserClientId())) {
                        return chain.filter(exchange); // interactive user — not this filter's concern
                    }

                    String path   = exchange.getRequest().getPath().value();
                    String method = exchange.getRequest().getMethod().name();
                    String targetService = RequestTargetResolver.targetService(path);

                    PolicyEvaluation eval = policyMatcher.evaluate(
                            policyDefinitionStore.current().service2service(),
                            List.of(callerService), targetService, path, method);

                    RuleEffect resolved = switch (eval.outcome()) {
                        case DENIED -> RuleEffect.DENY;
                        case ALLOWED -> RuleEffect.ALLOW;
                        case NO_MATCH -> policyDefaults.getDefaultEffect();
                    };
                    String matchedRuleId = eval.matchedRule() != null ? eval.matchedRule().id() : null;

                    if (resolved == RuleEffect.DENY) {
                        log.warn("SVC2SVC-DENY caller={} target={} method={} path={}",
                                callerService, targetService, method, path);
                        ZteAuditLogger.policyDecision("service2service", callerService, targetService,
                                matchedRuleId, "DENY");
                        return writeForbidden(exchange);
                    }

                    log.debug("SVC2SVC-ALLOW caller={} target={} method={} path={}",
                            callerService, targetService, method, path);
                    ZteAuditLogger.policyDecision("service2service", callerService, targetService,
                            matchedRuleId, "ALLOW");
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ALREADY_ROUTED_ATTR, true);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = response.bufferFactory().wrap(FORBIDDEN_BODY);
        return response.writeWith(Mono.just(buffer));
    }
}
