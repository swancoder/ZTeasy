package com.zte.gateway.identity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdentityUrn#parse}.
 */
class IdentityUrnTest {

    @Test
    void noPrefix_impliesRole() {
        assertThat(IdentityUrn.parse("ADMIN"))
                .contains(new IdentityUrn(IdentityType.ROLE, "ADMIN"));
    }

    @Test
    void userPrefix_parsesToUser() {
        assertThat(IdentityUrn.parse("user:zte-admin"))
                .contains(new IdentityUrn(IdentityType.USER, "zte-admin"));
    }

    @Test
    void groupPrefix_parsesToGroup() {
        assertThat(IdentityUrn.parse("group:ops-team"))
                .contains(new IdentityUrn(IdentityType.GROUP, "ops-team"));
    }

    @Test
    void rolePrefix_parsesToRole() {
        assertThat(IdentityUrn.parse("role:ADMIN"))
                .contains(new IdentityUrn(IdentityType.ROLE, "ADMIN"));
    }

    @Test
    void unknownPrefix_treatedAsLiteralRoleName() {
        assertThat(IdentityUrn.parse("agent:foo"))
                .contains(new IdentityUrn(IdentityType.ROLE, "agent:foo"));
    }

    @Test
    void clientPrefix_parsesToClient() {
        assertThat(IdentityUrn.parse("client:agent-a", IdentityType.ROLE))
                .contains(new IdentityUrn(IdentityType.CLIENT, "agent-a"));
    }

    @Test
    void noPrefix_usesCallerSuppliedDefaultType() {
        assertThat(IdentityUrn.parse("agent-a", IdentityType.CLIENT))
                .contains(new IdentityUrn(IdentityType.CLIENT, "agent-a"));
    }

    @Test
    void explicitPrefix_overridesDefaultTypeRegardless() {
        // A service2service/agentMcpToolCalls rule (default CLIENT) using role: explicitly
        // still means ROLE — the default only applies to a bare, unprefixed source.
        assertThat(IdentityUrn.parse("role:ADMIN", IdentityType.CLIENT))
                .contains(new IdentityUrn(IdentityType.ROLE, "ADMIN"));
    }

    @Test
    void oneArgOverload_isEquivalentToRoleDefault() {
        assertThat(IdentityUrn.parse("ADMIN")).isEqualTo(IdentityUrn.parse("ADMIN", IdentityType.ROLE));
    }

    @Test
    void wildcardSource_isNotCheckable() {
        assertThat(IdentityUrn.parse("*")).isEqualTo(Optional.empty());
        assertThat(IdentityUrn.parse("role:AD*")).isEqualTo(Optional.empty());
        assertThat(IdentityUrn.parse("user:?dmin")).isEqualTo(Optional.empty());
    }

    @Test
    void nullOrEmptySource_isNotCheckable() {
        assertThat(IdentityUrn.parse(null)).isEqualTo(Optional.empty());
        assertThat(IdentityUrn.parse("")).isEqualTo(Optional.empty());
    }
}
