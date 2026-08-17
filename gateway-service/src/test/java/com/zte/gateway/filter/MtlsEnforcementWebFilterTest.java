package com.zte.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MtlsEnforcementWebFilter} (ADR-018).
 *
 * <p>{@link SslInfo} has no public, instantiable implementation on this
 * classpath ({@code DefaultSslInfo} is package-private in {@code
 * spring-web}), so peer-certificate presence/absence is simulated with a
 * Mockito mock rather than a real handshake artifact — this filter only
 * ever checks array presence/length, never certificate content, so a mock
 * {@link X509Certificate} is sufficient.
 */
@ExtendWith(MockitoExtension.class)
class MtlsEnforcementWebFilterTest {

    @Mock WebFilterChain chain;
    @Mock SslInfo sslInfo;
    @Mock X509Certificate peerCert;

    private MockServerWebExchange exchange(String path, SslInfo sslInfoOrNull) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (sslInfoOrNull != null) {
            builder.sslInfo(sslInfoOrNull);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void protectedPath_validPeerCert_passesThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        when(sslInfo.getPeerCertificates()).thenReturn(new X509Certificate[]{peerCert});
        MockServerWebExchange ex = exchange("/sse", sslInfo);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void protectedPath_noSslInfo_isRejectedWith401() {
        MockServerWebExchange ex = exchange("/sse", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPath_sslInfoWithNoPeerCerts_isRejectedWith401() {
        when(sslInfo.getPeerCertificates()).thenReturn(new X509Certificate[0]);
        MockServerWebExchange ex = exchange("/sse", sslInfo);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedS2sPath_noSslInfo_isRejectedWith401() {
        MockServerWebExchange ex = exchange("/api/v1/service-a/hello", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminApi_noSslInfo_isExcludedAndPassesThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/api/v1/admin/policies", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void internalApi_noSslInfo_isExcludedAndPassesThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/api/v1/internal/policies", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
    }

    @Test
    void adminUiStaticAsset_noSslInfo_isExcludedAndPassesThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/admin/index.html", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(true).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
    }

    @Test
    void mtlsDisabled_protectedPath_noSslInfo_stillPassesThrough() {
        // application-it.yml sets zte.mtls.enabled=false — the filter must be a
        // pure no-op there regardless of path, matching MtlsHttpClientConfig's
        // own @ConditionalOnProperty default-true semantics for the same key.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/sse", null);

        StepVerifier.create(new MtlsEnforcementWebFilter(false).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
}
