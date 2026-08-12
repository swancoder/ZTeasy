package com.zte.gateway.admin;

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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminAuthorizationFilter}.
 *
 * <p>This exists because {@code ZteAuthorizationFilter} (a Gateway {@code GlobalFilter})
 * does <em>not</em> run for {@code /api/v1/admin/**} — that path has no
 * {@code InventoryRouteDefinitionLocator} route, and {@code GlobalFilter}s only fire for routed
 * requests. Found empirically while building ADR-012: a USER-role JWT initially got
 * {@code 200} from the admin API because no authorization check ran at all. These
 * tests pin down the fix so it can't silently regress.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthorizationFilterTest {

    @Mock PolicyDefinitionStore policyDefinitionStore;
    @Mock WebFilterChain        chain;

    private final PolicyMatcher matcher = new PolicyMatcher();
    AdminAuthorizationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminAuthorizationFilter(policyDefinitionStore, matcher);
    }

    private JwtAuthenticationToken jwtAuth(List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("user-uuid-123")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private void withYamlRules(PolicyRule... rules) {
        when(policyDefinitionStore.current())
                .thenReturn(new PolicyDocument(1, List.of(rules), List.of(), List.of()));
    }

    @Test
    void nonAdminPath_passesThroughWithoutTouchingPolicy() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/sse");

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
        verifyNoInteractions(policyDefinitionStore);
    }

    @Test
    void adminRole_matchingYamlAllow_callsChain() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        withYamlRules(new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "admin", "/api/v1/admin/**", "*", 0));

        MockServerWebExchange  ex   = exchange("/api/v1/admin/policies");
        JwtAuthenticationToken auth = jwtAuth(List.of("ADMIN"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(ex);
    }

    /**
     * ADR-014: a YAML rule whose source is a {@code role:}-prefixed URN matches
     * identically to the bare role-name form.
     */
    @Test
    void adminRole_urnPrefixedYamlAllow_callsChain() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        withYamlRules(new PolicyRule("a1", RuleEffect.ALLOW, "role:ADMIN", "admin", "/api/v1/admin/**", "*", 0));

        MockServerWebExchange  ex   = exchange("/api/v1/admin/policies");
        JwtAuthenticationToken auth = jwtAuth(List.of("ADMIN"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(ex);
    }

    @Test
    void userRole_noMatchingYamlRule_returns403AndNeverCallsChain() {
        withYamlRules(new PolicyRule("a1", RuleEffect.ALLOW, "ADMIN", "admin", "/api/v1/admin/**", "*", 0));

        MockServerWebExchange  ex   = exchange("/api/v1/admin/policies");
        JwtAuthenticationToken auth = jwtAuth(List.of("USER"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void explicitYamlDeny_returns403() {
        withYamlRules(new PolicyRule("d1", RuleEffect.DENY, "ADMIN", "admin", "/api/v1/admin/**", "*", 0));

        MockServerWebExchange  ex   = exchange("/api/v1/admin/policies");
        JwtAuthenticationToken auth = jwtAuth(List.of("ADMIN"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
