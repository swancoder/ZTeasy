package com.zte.gateway.filter;

import com.zte.gateway.policy.def.PolicyDefaultsProperties;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyMatcher;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceToServiceAuthorizationFilter}.
 */
@ExtendWith(MockitoExtension.class)
class ServiceToServiceAuthorizationFilterTest {

    @Mock PolicyDefinitionStore policyDefinitionStore;
    @Mock GatewayFilterChain    chain;

    private final PolicyMatcher matcher = new PolicyMatcher();
    private PolicyDefaultsProperties defaults;
    private ServiceToServiceAuthorizationFilter filter;

    @BeforeEach
    void setUp() {
        defaults = new PolicyDefaultsProperties();
        defaults.setDefaultEffect(RuleEffect.DENY);
        defaults.setUserClientId("zte-gateway");
        filter = new ServiceToServiceAuthorizationFilter(policyDefinitionStore, matcher, defaults);
    }

    private JwtAuthenticationToken jwtWithAzp(String azp) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("client-uuid")
                .claim("azp", azp)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/service-a/hello").build());
    }

    private void withRules(PolicyRule... rules) {
        when(policyDefinitionStore.current())
                .thenReturn(new PolicyDocument(1, List.of(), List.of(rules), List.of()));
    }

    @Test
    void interactiveUserToken_passesThroughWithoutPolicyLookup() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        JwtAuthenticationToken auth = jwtWithAzp("zte-gateway");

        StepVerifier.create(filter.filter(exchange(), chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(any());
        verifyNoInteractions(policyDefinitionStore);
    }

    @Test
    void servicePrincipal_explicitAllowRule_callsChain() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        withRules(new PolicyRule("s1", RuleEffect.ALLOW, "agent-a", "service-a", null, null, 0));
        JwtAuthenticationToken auth = jwtWithAzp("agent-a");

        StepVerifier.create(filter.filter(exchange(), chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(any());
    }

    /**
     * ADR-015: a YAML rule whose source is a {@code client:}-prefixed URN matches
     * identically to the bare client-id form — {@link com.zte.gateway.identity.IdentitySources#enrichClient}
     * enriches the sources list with {@code client:<clientId>}.
     */
    @Test
    void servicePrincipal_urnPrefixedAllowRule_callsChain() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        withRules(new PolicyRule("s1", RuleEffect.ALLOW, "client:agent-a", "service-a", null, null, 0));
        JwtAuthenticationToken auth = jwtWithAzp("agent-a");

        StepVerifier.create(filter.filter(exchange(), chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void servicePrincipal_explicitDenyRule_returns403() {
        withRules(new PolicyRule("s1", RuleEffect.DENY, "agent-a", "service-a", null, null, 0));
        JwtAuthenticationToken auth = jwtWithAzp("agent-a");
        MockServerWebExchange ex = exchange();

        StepVerifier.create(filter.filter(ex, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verifyNoInteractions(chain);
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void servicePrincipal_noMatchingRule_defaultsToDeny() {
        withRules();
        JwtAuthenticationToken auth = jwtWithAzp("agent-a");
        MockServerWebExchange ex = exchange();

        StepVerifier.create(filter.filter(ex, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
