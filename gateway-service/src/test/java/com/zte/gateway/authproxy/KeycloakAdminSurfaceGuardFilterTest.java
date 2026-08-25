package com.zte.gateway.authproxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KeycloakAdminSurfaceGuardFilter} (ADR-027 amendment) —
 * the OIDC login flow must keep working through the public {@code /auth}
 * proxy while Keycloak's admin console and master realm stay unreachable.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakAdminSurfaceGuardFilterTest {

    @Mock WebFilterChain chain;

    private final KeycloakAdminSurfaceGuardFilter filter = new KeycloakAdminSurfaceGuardFilter();

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    @Test
    void adminConsole_isBlockedWith404() {
        MockServerWebExchange ex = exchange("/auth/admin/master/console/");

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void masterRealm_isBlocked() {
        MockServerWebExchange ex = exchange("/auth/realms/master/protocol/openid-connect/auth");

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void productRealmLoginAndTokenEndpoints_passThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());

        for (String path : new String[]{
                "/auth/realms/zte-realm/protocol/openid-connect/auth",
                "/auth/realms/zte-realm/protocol/openid-connect/token",
                "/auth/realms/zte-realm/.well-known/openid-configuration",
                "/auth/resources/abc/login/keycloak/css/login.css"}) {
            MockServerWebExchange ex = exchange(path);
            StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
            assertThat(ex.getResponse().getStatusCode()).as(path).isNull();
        }
        verify(chain, times(4)).filter(any());
    }

    @Test
    void operationalEndpoints_areBlocked() {
        assertThat(KeycloakAdminSurfaceGuardFilter.isAdminSurface("/auth/metrics")).isTrue();
        assertThat(KeycloakAdminSurfaceGuardFilter.isAdminSurface("/auth/health/ready")).isTrue();
        assertThat(KeycloakAdminSurfaceGuardFilter.isAdminSurface("/auth/admin")).isTrue();
        assertThat(KeycloakAdminSurfaceGuardFilter.isAdminSurface("/auth/realms/master")).isTrue();
    }

    @Test
    void nonAuthPaths_areUntouched() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/admin/index.html");

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
    }
}
