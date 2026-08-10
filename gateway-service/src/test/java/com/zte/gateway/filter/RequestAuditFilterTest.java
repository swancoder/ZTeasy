package com.zte.gateway.filter;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RequestAuditFilter}.
 *
 * <p>Covers the ADR-013 rewrite: {@code X-Request-Id} passthrough/generation,
 * trusted {@code X-User-Id} injection (unconditionally stripped now, unlike
 * the pre-ADR-013 version which only stripped it on the JWT branch — see the
 * class Javadoc), and that exactly one {@link RequestLogAuditService#record}
 * call happens per request via {@code doFinally}, regardless of outcome.
 */
@ExtendWith(MockitoExtension.class)
class RequestAuditFilterTest {

    @Mock RequestLogAuditService auditService;
    @Mock WebFilterChain          chain;

    RequestAuditFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestAuditFilter(auditService);
    }

    private JwtAuthenticationToken jwtAuth(String subject) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("azp", "zte-gateway")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private MockServerWebExchange exchangeWithHeaders(Map<String, String> headers) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/v1/service-a/hello");
        headers.forEach(builder::header);
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void missingRequestId_generatesOne_andForwardsItDownstream() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithHeaders(Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        String forwardedTraceId = captor.getValue().getRequest().getHeaders().getFirst("X-Request-Id");
        assertThat(forwardedTraceId).isNotBlank();
        assertThat(UUID.fromString(forwardedTraceId)).isNotNull(); // doesn't throw -> valid UUID

        ArgumentCaptor<RequestLog> logCaptor = ArgumentCaptor.forClass(RequestLog.class);
        verify(auditService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().traceId()).isEqualTo(forwardedTraceId);
    }

    @Test
    void existingRequestId_isPreservedAndForwarded() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithHeaders(Map.of("X-Request-Id", "caller-supplied-id"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("caller-supplied-id");

        ArgumentCaptor<RequestLog> logCaptor = ArgumentCaptor.forClass(RequestLog.class);
        verify(auditService).record(logCaptor.capture());
        assertThat(logCaptor.getValue().traceId()).isEqualTo("caller-supplied-id");
    }

    @Test
    void jwtPresent_stripsSpoofedXUserId_andInjectsTrustedSubject() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithHeaders(Map.of("X-User-Id", "attacker-supplied"));
        JwtAuthenticationToken auth = jwtAuth("real-subject-uuid");

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().get("X-User-Id"))
                .containsExactly("real-subject-uuid");
    }

    @Test
    void noJwt_spoofedXUserIdHeader_isStillStripped() {
        // Unlike the pre-ADR-013 version, X-User-Id is stripped unconditionally —
        // matches this filter's own stated Zero Trust principle even when there's
        // no JWT to derive a trusted replacement from.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithHeaders(Map.of("X-User-Id", "attacker-supplied"));

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().get("X-User-Id")).isNullOrEmpty();
    }

    @Test
    void auditServiceRecord_calledExactlyOncePerRequest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithHeaders(Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verify(auditService, times(1)).record(any());
    }
}
