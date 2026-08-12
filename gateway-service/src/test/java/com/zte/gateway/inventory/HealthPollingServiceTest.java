package com.zte.gateway.inventory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link HealthPollingService#statusTransition} and {@link
 * HealthPollingService#healthCheckUrl} — the pure decision logic. Everything
 * else in this class calls a real {@code WebClient} (the {@code
 * /actuator/health} ping), proven only via {@code InventoryRegistryIT}
 * against a real WireMock target — same precedent {@code KeycloakIdpAdapter}
 * established (never unit-tested with a mocked {@code WebClient}).
 */
class HealthPollingServiceTest {

    @Test
    void activeAndUnreachable_transitionsToDown() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.ACTIVE, false))
                .contains(InventoryStatus.DOWN);
    }

    @Test
    void downAndReachable_transitionsToActive() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.DOWN, true))
                .contains(InventoryStatus.ACTIVE);
    }

    @Test
    void activeAndReachable_noTransition() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.ACTIVE, true))
                .isEqualTo(Optional.empty());
    }

    @Test
    void downAndUnreachable_noTransition() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.DOWN, false))
                .isEqualTo(Optional.empty());
    }

    @Test
    void warning_neverTransitions_regardlessOfReachability() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.WARNING, true)).isEqualTo(Optional.empty());
        assertThat(HealthPollingService.statusTransition(InventoryStatus.WARNING, false)).isEqualTo(Optional.empty());
    }

    @Test
    void pending_neverTransitions_regardlessOfReachability() {
        assertThat(HealthPollingService.statusTransition(InventoryStatus.PENDING, true)).isEqualTo(Optional.empty());
        assertThat(HealthPollingService.statusTransition(InventoryStatus.PENDING, false)).isEqualTo(Optional.empty());
    }

    @Test
    void healthCheckUrl_managementUrlSet_prefersManagementUrl() {
        InventoryEntry entry = entry("https://api-host:8081", "http://mgmt-host:9081");
        assertThat(HealthPollingService.healthCheckUrl(entry)).isEqualTo("http://mgmt-host:9081");
    }

    @Test
    void healthCheckUrl_managementUrlNull_fallsBackToBaseUrl() {
        InventoryEntry entry = entry("https://api-host:8081", null);
        assertThat(HealthPollingService.healthCheckUrl(entry)).isEqualTo("https://api-host:8081");
    }

    @Test
    void healthCheckUrl_managementUrlBlank_fallsBackToBaseUrl() {
        InventoryEntry entry = entry("https://api-host:8081", "   ");
        assertThat(HealthPollingService.healthCheckUrl(entry)).isEqualTo("https://api-host:8081");
    }

    private static InventoryEntry entry(String baseUrl, String managementUrl) {
        return new InventoryEntry(UUID.randomUUID(), "svc", TargetType.REST, baseUrl, null, managementUrl,
                InventoryStatus.ACTIVE, Instant.now());
    }
}
