package com.zte.gateway.mcp.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Who a tool call is attributed to, and in what order scope is looked up (ADR-039). */
class McpCallerTest {

    @Test
    void agent_keepsTheClientVocabularyItAlwaysHad() {
        McpCaller caller = McpCaller.client("crm-account-health-emea-01");

        assertThat(caller.human()).isFalse();
        assertThat(caller.sources()).contains("client:crm-account-health-emea-01");
        assertThat(caller.acapKeys()).containsExactly("crm-account-health-emea-01");
    }

    @Test
    void person_isTheSubject_withRolesAndTheirApplicationAlsoAvailableToRules() {
        McpCaller caller = McpCaller.user("anna", List.of("CHAT_USER", "SALES_EMEA"), "zte-chat-ui");

        assertThat(caller.human()).isTrue();
        assertThat(caller.id()).isEqualTo("anna");
        assertThat(caller.sources()).containsSubsequence("user:anna", "role:CHAT_USER");
        assertThat(caller.sources()).contains("SALES_EMEA");           // ADR-014 bare form
        assertThat(caller.sources()).contains("client:zte-chat-ui");   // the front door, last
        assertThat(caller.sources().indexOf("user:anna"))
                .isLessThan(caller.sources().indexOf("client:zte-chat-ui"));
    }

    /**
     * Scope is looked up by the person first and by their roles after, so an
     * organisation writes one profile for "sales in EMEA" and still keeps the
     * ability to give one individual their own.
     */
    @Test
    void person_scopeLookupPrefersTheIndividualThenTheirRoles() {
        McpCaller caller = McpCaller.user("anna", List.of("CHAT_USER", "SALES_EMEA"), "zte-chat-ui");

        assertThat(caller.acapKeys()).containsExactly("user:anna", "role:CHAT_USER", "role:SALES_EMEA");
    }

    @Test
    void person_withoutRoles_stillHasAnIdentity() {
        McpCaller caller = McpCaller.user("anna", null, null);

        assertThat(caller.sources()).containsExactly("user:anna");
        assertThat(caller.acapKeys()).containsExactly("user:anna");
    }
}
