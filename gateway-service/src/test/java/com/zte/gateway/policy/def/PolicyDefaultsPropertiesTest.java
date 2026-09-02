package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which tokens carry a person (ADR-039).
 *
 * <p>This existed as a single client id, and the consequence was invisible for a
 * long time: the browser consoles were classified as service principals, and it
 * did not matter because they only called gateway-local paths, decided elsewhere.
 * The first SPA to call a ROUTED path was refused for being a machine. These tests
 * pin the distinction that actually matters — a browser client is a person, an
 * agent's client-credentials identity is not.
 */
class PolicyDefaultsPropertiesTest {

    private final PolicyDefaultsProperties props = new PolicyDefaultsProperties();

    @Test
    void everyInteractiveConsoleCarriesAPerson() {
        assertThat(props.isUserClient("zte-gateway")).isTrue();
        assertThat(props.isUserClient("zte-admin-ui")).isTrue();
        assertThat(props.isUserClient("zte-approver-ui")).isTrue();
        assertThat(props.isUserClient("zte-chat-ui")).isTrue();
    }

    @Test
    void anAgentIsStillAMachine() {
        assertThat(props.isUserClient("agent-a")).isFalse();
        assertThat(props.isUserClient("crm-account-health-emea-01")).isFalse();
        assertThat(props.isUserClient("service-a")).isFalse();
        assertThat(props.isUserClient(null)).isFalse();
    }

    /** An operator can narrow or extend the list without touching code. */
    @Test
    void theListIsConfigurable_andTheLegacySingleValueStillCounts() {
        props.setUserClientIds(List.of("only-this-one"));
        props.setUserClientId("legacy-client");

        assertThat(props.isUserClient("only-this-one")).isTrue();
        assertThat(props.isUserClient("legacy-client")).isTrue();
        assertThat(props.isUserClient("zte-chat-ui")).isFalse();
    }
}
