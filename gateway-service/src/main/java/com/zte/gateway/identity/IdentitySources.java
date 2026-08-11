package com.zte.gateway.identity;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the enriched {@code sources} list passed to {@link
 * com.zte.gateway.policy.def.PolicyMatcher#evaluate}: bare realm role names
 * (unchanged — every existing bare-role YAML rule keeps matching) plus
 * {@code role:<r>}/{@code user:<name>}/{@code group:<g>} URNs (ADR-014).
 *
 * <p>Only for {@code users2service} evaluation. Callers keep using the bare
 * {@code roles} list (not this enriched one) for anything besides the match
 * call itself — the service2service dispatch check and audit logging both
 * want the original, unenriched role list.
 */
public final class IdentitySources {

    private IdentitySources() {}

    public static List<String> enrich(List<String> realmRoles, JwtAuthenticationToken jwtAuth) {
        List<String> sources = new ArrayList<>(realmRoles);
        for (String role : realmRoles) {
            sources.add("role:" + role);
        }

        String username = jwtAuth.getToken().getClaimAsString("preferred_username");
        if (username != null) {
            sources.add("user:" + username);
        }

        List<String> groups = jwtAuth.getToken().getClaimAsStringList("groups");
        if (groups != null) {
            for (String group : groups) {
                sources.add("group:" + group);
            }
        }

        return sources;
    }

    /**
     * The {@code service2service}/{@code agentMcpToolCalls} counterpart of
     * {@link #enrich} (ADR-015): the bare OAuth2 client id (unchanged — every
     * existing bare-clientId YAML rule keeps matching) plus its {@code
     * client:<clientId>} URN form.
     */
    public static List<String> enrichClient(String clientId) {
        return List.of(clientId, "client:" + clientId);
    }
}
