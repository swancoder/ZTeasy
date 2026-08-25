package com.zte.gateway.internal;

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
 * Unit tests for {@link InternalEndpointGuardFilter} (ADR-027 amendment).
 *
 * <p>Pins down the fix for a live finding: behind a public ingress,
 * {@code /api/v1/internal/**} — unauthenticated by design on the assumption
 * of a private network — was answering anonymous callers from the internet.
 */
@ExtendWith(MockitoExtension.class)
class InternalEndpointGuardFilterTest {

    private static final String KEY = "s3cret-internal-key";

    @Mock WebFilterChain chain;

    private MockServerWebExchange exchange(String path, String keyHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (keyHeader != null) {
            builder.header(InternalEndpointGuardFilter.HEADER, keyHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void internalPath_noKeyHeader_isRefusedWith403() {
        MockServerWebExchange ex = exchange("/api/v1/internal/policies", null);

        StepVerifier.create(new InternalEndpointGuardFilter(KEY).filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void internalPath_wrongKey_isRefusedWith403() {
        MockServerWebExchange ex = exchange("/api/v1/internal/policies", "not-the-key");

        StepVerifier.create(new InternalEndpointGuardFilter(KEY).filter(ex, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void internalPath_correctKey_passesThrough() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/api/v1/internal/policies", KEY);

        StepVerifier.create(new InternalEndpointGuardFilter(KEY).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void nonInternalPath_isUntouchedEvenWithoutKey() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/api/v1/admin/policies", null);

        StepVerifier.create(new InternalEndpointGuardFilter(KEY).filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
    }

    /** No key configured = the pre-ADR-027 behavior local dev relies on. */
    @Test
    void noKeyConfigured_internalPathStaysOpen() {
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange ex = exchange("/api/v1/internal/policies", null);

        StepVerifier.create(new InternalEndpointGuardFilter("").filter(ex, chain)).verifyComplete();

        verify(chain).filter(ex);
    }

    @Test
    void blankOrNullConfiguredKey_isTreatedAsDisabled() {
        when(chain.filter(any())).thenReturn(Mono.empty());

        for (String configured : new String[]{null, "", "   "}) {
            MockServerWebExchange ex = exchange("/api/v1/internal/policies", null);
            StepVerifier.create(new InternalEndpointGuardFilter(configured).filter(ex, chain)).verifyComplete();
            assertThat(ex.getResponse().getStatusCode()).isNull();
        }
        verify(chain, times(3)).filter(any());
    }
}
