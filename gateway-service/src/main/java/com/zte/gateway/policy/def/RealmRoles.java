package com.zte.gateway.policy.def;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

/**
 * Extracts realm-level roles from the {@code realm_access.roles} JWT claim —
 * shared by every enforcement point that needs a caller's roles for YAML
 * {@code users2service} evaluation ({@code ZteAuthorizationFilter}, the admin
 * package's {@code AdminAuthorizationFilter}), so the claim shape is read
 * identically everywhere.
 */
public final class RealmRoles {

    private RealmRoles() {}

    public static List<String> extract(JwtAuthenticationToken jwtAuth) {
        Map<String, Object> realmAccess = jwtAuth.getToken().getClaimAsMap("realm_access");
        if (realmAccess == null) return List.of();
        Object roles = realmAccess.get("roles");
        if (roles instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
