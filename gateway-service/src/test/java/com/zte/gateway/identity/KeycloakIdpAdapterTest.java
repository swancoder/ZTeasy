package com.zte.gateway.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link KeycloakIdpAdapter#isSystemClient} (ADR-016) — the
 * one pure, self-contained piece of this adapter; everything else is only
 * proven live via {@code IdentitySyncIT} against a real Keycloak (the
 * established precedent since ADR-014, this adapter has never had a mocked
 * {@code WebClient} unit test).
 */
class KeycloakIdpAdapterTest {

    @Test
    void exactMatches_areSystemClients() {
        assertThat(KeycloakIdpAdapter.isSystemClient("account")).isTrue();
        assertThat(KeycloakIdpAdapter.isSystemClient("broker")).isTrue();
        assertThat(KeycloakIdpAdapter.isSystemClient("realm-management")).isTrue();
        assertThat(KeycloakIdpAdapter.isSystemClient("admin-cli")).isTrue();
        assertThat(KeycloakIdpAdapter.isSystemClient("security-admin-console")).isTrue();
    }

    @Test
    void prefixedSatelliteClients_areSystemClients() {
        assertThat(KeycloakIdpAdapter.isSystemClient("account-console")).isTrue();
    }

    @Test
    void businessClients_areNotSystemClients() {
        assertThat(KeycloakIdpAdapter.isSystemClient("agent-a")).isFalse();
        assertThat(KeycloakIdpAdapter.isSystemClient("agent-b")).isFalse();
        assertThat(KeycloakIdpAdapter.isSystemClient("zte-gateway")).isFalse();
        assertThat(KeycloakIdpAdapter.isSystemClient("zte-admin-ui")).isFalse();
    }
}
