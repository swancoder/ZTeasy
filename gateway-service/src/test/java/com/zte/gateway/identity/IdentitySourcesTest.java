package com.zte.gateway.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdentitySources#enrich}.
 */
class IdentitySourcesTest {

    private JwtAuthenticationToken jwtAuth(List<String> roles, String username, List<String> groups) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("user-uuid-123")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (username != null) builder.claim("preferred_username", username);
        if (groups != null) builder.claim("groups", groups);
        return new JwtAuthenticationToken(builder.build());
    }

    @Test
    void enrich_addsBareRolesAndRolePrefixedUrns() {
        List<String> sources = IdentitySources.enrich(List.of("ADMIN", "USER"), jwtAuth(List.of("ADMIN", "USER"), null, null));

        assertThat(sources).contains("ADMIN", "USER", "role:ADMIN", "role:USER");
    }

    @Test
    void enrich_addsUserUrnFromPreferredUsername() {
        List<String> sources = IdentitySources.enrich(List.of("ADMIN"), jwtAuth(List.of("ADMIN"), "zte-admin", null));

        assertThat(sources).contains("user:zte-admin");
    }

    @Test
    void enrich_addsGroupUrnsFromGroupsClaim() {
        List<String> sources = IdentitySources.enrich(List.of(), jwtAuth(List.of(), null, List.of("ops-team", "sre")));

        assertThat(sources).contains("group:ops-team", "group:sre");
    }

    @Test
    void enrich_noUsernameOrGroupsClaim_onlyRoleUrns() {
        List<String> sources = IdentitySources.enrich(List.of("ADMIN"), jwtAuth(List.of("ADMIN"), null, null));

        assertThat(sources).containsExactlyInAnyOrder("ADMIN", "role:ADMIN");
    }
}
