package com.zte.gateway.inventory;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link HealthPollingService#statusTransition} — the pure
 * {@code ACTIVE}<->{@code DOWN} decision logic. Everything else in this
 * class calls a real {@code WebClient} (the {@code /actuator/health} ping),
 * proven only via {@code InventoryRegistryIT} against a real WireMock
 * target — same precedent {@code KeycloakIdpAdapter} established (never
 * unit-tested with a mocked {@code WebClient}).
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
}
