package com.zte.gateway.audit;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientIpResolver} — same "pure decision logic gets a
 * direct unit test" precedent as {@code HealthPollingService.statusTransition}.
 */
class ClientIpResolverTest {

    @Test
    void forwardedForHeader_takesPrecedence_firstHopOnly() {
        InetSocketAddress remote = socketAddress("203.0.113.9");
        assertThat(ClientIpResolver.resolve("198.51.100.1, 203.0.113.9", remote)).isEqualTo("198.51.100.1");
    }

    @Test
    void blankForwardedForHeader_fallsBackToRemoteAddress() {
        InetSocketAddress remote = socketAddress("203.0.113.9");
        assertThat(ClientIpResolver.resolve("  ", remote)).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv4Loopback_staysIpv4() {
        InetSocketAddress remote = socketAddress("127.0.0.1");
        assertThat(ClientIpResolver.resolve(null, remote)).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6Loopback_normalizedToIpv4() {
        // The actual bug this fixes: localhost resolves to both 127.0.0.1 and ::1, and
        // which one a given dev connection picks is happenstance (curl vs. Python's
        // requests, different runs of the same tool) — both mean "this machine."
        InetSocketAddress remote = socketAddress("::1");
        assertThat(ClientIpResolver.resolve(null, remote)).isEqualTo("127.0.0.1");
    }

    @Test
    void nonLoopbackAddress_isNotNormalized() {
        InetSocketAddress remote = socketAddress("172.18.0.5");
        assertThat(ClientIpResolver.resolve(null, remote)).isEqualTo("172.18.0.5");
    }

    @Test
    void nullRemoteAddress_returnsNull() {
        assertThat(ClientIpResolver.resolve(null, null)).isNull();
    }

    private static InetSocketAddress socketAddress(String host) {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), 0);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
}
