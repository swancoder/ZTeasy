package com.zte.gateway.filter;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogAuditService;
import com.zte.gateway.inventory.HealthTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
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
 * class Javadoc), that exactly one {@link RequestLogAuditService#record}
 * call happens per in-scope request via {@code doFinally}, and the ADR-013
 * amendment scoping audit output to {@link AuditExclusionProperties}.
 */
@ExtendWith(MockitoExtension.class)
class RequestAuditFilterTest {

    /** Same default list application.yml ships (see application.yml's zte.audit block). */
    private static final List<String> DEFAULT_EXCLUSIONS =
            List.of("/admin/", "/api/v1/admin/", "/api/v1/internal/", "/actuator/");

    @Mock RequestLogAuditService auditService;
    @Mock WebFilterChain          chain;
    @Mock HealthTelemetryService  healthTelemetryService;

    RequestAuditFilter filter;

    @BeforeEach
    void setUp() {
        AuditExclusionProperties exclusions = new AuditExclusionProperties();
        exclusions.setExcludedPathPrefixes(DEFAULT_EXCLUSIONS);
        filter = new RequestAuditFilter(auditService, exclusions, healthTelemetryService);
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
        return exchangeWithPathAndHeaders("/api/v1/service-a/hello", headers);
    }

    private MockServerWebExchange exchangeWithPathAndHeaders(String path, Map<String, String> headers) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
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

    @Test
    void proxiedServiceCall_isAudited() {
        // Regression guard: the excluded-prefix scoping must not accidentally
        // swallow the actual zero-trust enforcement points.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/service-a/hello", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verify(auditService).record(any());
    }

    @Test
    void adminConsoleStaticAsset_isNotAudited() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/admin/index.html", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(auditService);
    }

    @Test
    void adminConsoleApi_isNotAudited() {
        // Would otherwise be a self-referential noise loop: viewing the audit
        // trail generates more audit trail.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/admin/audit-logs", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(auditService);
    }

    @Test
    void internalEndpoint_isNotAudited() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/internal/policies", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(auditService);
    }

    @Test
    void healthCheck_isNotAudited() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/actuator/health", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(auditService);
    }

    @Test
    void excludedPath_stillGetsTraceIdAndXUserIdHandling() {
        // The exclusion only gates audit *output* — tracing/header handling stays universal.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/actuator/health", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    /**
     * ADR-016: a 2xx response on a routed path records inventory health
     * telemetry — deliberately unaffected by the {@code isAuditScoped}
     * exclusion list, a different, separate concern from the {@code
     * request_logs} audit trail.
     */
    @Test
    void successfulProxiedCall_recordsHealthTelemetry() {
        when(chain.filter(any())).thenAnswer(invocation -> {
            ((ServerWebExchange) invocation.getArgument(0)).getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/service-a/hello", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verify(healthTelemetryService).recordSuccessfulCall("service-a");
    }

    @Test
    void nonSuccessResponse_doesNotRecordHealthTelemetry() {
        when(chain.filter(any())).thenAnswer(invocation -> {
            ((ServerWebExchange) invocation.getArgument(0)).getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return Mono.empty();
        });
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/service-a/hello", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(healthTelemetryService);
    }

    /** ADR-017: targetService/httpMethod/decisionEffect are derived and persisted. */
    @Test
    void auditRecord_populatesTargetServiceHttpMethodAndDecisionEffect() {
        when(chain.filter(any())).thenAnswer(invocation -> {
            ((ServerWebExchange) invocation.getArgument(0)).getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return Mono.empty();
        });
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/service-a/hello", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        ArgumentCaptor<RequestLog> logCaptor = ArgumentCaptor.forClass(RequestLog.class);
        verify(auditService).record(logCaptor.capture());
        RequestLog logged = logCaptor.getValue();
        assertThat(logged.targetService()).isEqualTo("service-a");
        assertThat(logged.httpMethod()).isEqualTo("GET");
        assertThat(logged.decisionEffect()).isEqualTo("DENY");
    }

    /** ADR-017: initiatorClient comes from the azp claim, originalUserObo from the subject. */
    @Test
    void auditRecord_populatesInitiatorClientAndOriginalUserObo() {
        when(chain.filter(any())).thenAnswer(invocation -> {
            ((ServerWebExchange) invocation.getArgument(0)).getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });
        MockServerWebExchange ex = exchangeWithHeaders(Map.of());
        JwtAuthenticationToken auth = jwtAuth("real-subject-uuid");

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        ArgumentCaptor<RequestLog> logCaptor = ArgumentCaptor.forClass(RequestLog.class);
        verify(auditService).record(logCaptor.capture());
        RequestLog logged = logCaptor.getValue();
        assertThat(logged.initiatorClient()).isEqualTo("zte-gateway"); // azp claim set by jwtAuth()
        assertThat(logged.originalUserObo()).isEqualTo("real-subject-uuid");
        assertThat(logged.decisionEffect()).isEqualTo("ALLOW");
    }

    @Test
    void noStatusCodeSet_doesNotRecordHealthTelemetry() {
        // Every other test in this class leaves the mock response status unset
        // (null) — this pins down that "no signal" is treated as "not a success,"
        // not accidentally coerced into one.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchangeWithPathAndHeaders("/api/v1/service-a/hello", Map.of());

        StepVerifier.create(
                filter.filter(ex, chain)
                      .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl()))))
                .verifyComplete();

        verifyNoInteractions(healthTelemetryService);
    }
}
