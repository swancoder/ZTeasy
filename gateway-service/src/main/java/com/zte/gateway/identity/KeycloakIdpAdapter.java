package com.zte.gateway.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * {@link IdpClient} implementation for Keycloak's Admin REST API.
 *
 * <p>Obtains a fresh client-credentials token per {@code fetchX()} call — no
 * cross-call token caching. Keycloak's default 300s access-token lifespan is
 * shorter than the 15-min sync interval anyway, so caching across sync
 * cycles buys nothing, and reusing one token across the 4 calls within a
 * single sync isn't worth the added state for 4 cheap extra token requests
 * per cycle (ADR-014).
 *
 * <p>Reuses the {@code zte-gateway} client's existing service account
 * (granted {@code view-users}/{@code view-realm}/{@code view-clients}
 * realm-management roles in {@code keycloak/realm-export.json} — the last
 * one added by ADR-015) rather than a dedicated client.
 */
@Component
@ConditionalOnProperty(prefix = "zte.idp", name = "provider", havingValue = "keycloak", matchIfMissing = true)
public class KeycloakIdpAdapter implements IdpClient {

    private final WebClient client;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakIdpAdapter(WebClient.Builder builder,
                               @Value("${zte.idp.keycloak.base-uri:http://localhost:8180}") String baseUri,
                               @Value("${zte.idp.keycloak.realm:zte-realm}") String realm,
                               @Value("${zte.idp.keycloak.client-id:zte-gateway}") String clientId,
                               @Value("${zte.idp.keycloak.client-secret:zte-gateway-secret}") String clientSecret) {
        this.client = builder.baseUrl(baseUri).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Flux<IdpIdentity> fetchUsers() {
        return withToken(token -> client.get()
                .uri("/admin/realms/{realm}/users", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakUser.class)
                .map(u -> IdpIdentity.fetched(IdentityType.USER, u.id, u.username, u.displayName())));
    }

    @Override
    public Flux<IdpIdentity> fetchGroups() {
        return withToken(token -> client.get()
                .uri("/admin/realms/{realm}/groups", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakGroup.class)
                .map(g -> IdpIdentity.fetched(IdentityType.GROUP, g.id, g.name, g.name)));
    }

    @Override
    public Flux<IdpIdentity> fetchRoles() {
        return withToken(token -> client.get()
                .uri("/admin/realms/{realm}/roles", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakRole.class)
                .map(r -> IdpIdentity.fetched(IdentityType.ROLE, r.id, r.name, r.displayName())));
    }

    @Override
    public Flux<IdpIdentity> fetchClients() {
        return withToken(token -> client.get()
                .uri("/admin/realms/{realm}/clients", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakClient.class)
                .filter(c -> !isSystemClient(c.clientId))
                .map(c -> IdpIdentity.fetched(IdentityType.CLIENT, c.id, c.clientId, c.displayName())));
    }

    @Override
    public Flux<IdpRelation> fetchRelations() {
        return withToken(token -> Flux.merge(userRelations(token), clientRelations(token)));
    }

    private Flux<IdpRelation> userRelations(String token) {
        return client.get()
                .uri("/admin/realms/{realm}/users", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakUser.class)
                .flatMap(u -> Flux.merge(groupMemberships(token, u.id), userRoleAssignments(token, u.id)));
    }

    private Flux<IdpRelation> groupMemberships(String token, String userId) {
        return client.get()
                .uri("/admin/realms/{realm}/users/{userId}/groups", realm, userId)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakGroup.class)
                .map(g -> new IdpRelation(userId, g.id, RelationType.MEMBER_OF));
    }

    private Flux<IdpRelation> userRoleAssignments(String token, String userId) {
        return client.get()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", realm, userId)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakRole.class)
                .map(r -> new IdpRelation(userId, r.id, RelationType.HAS_ROLE));
    }

    /**
     * Machine identities' role assignments live on their <em>service
     * account user</em>, a separate Keycloak entity from the client itself
     * — one extra lookup per client to resolve it, then its realm
     * role-mappings. {@code onErrorResume}: a client without
     * {@code serviceAccountsEnabled} 404s on the service-account-user
     * lookup; skipped rather than failing the whole relations fetch for one
     * client, same resilience posture {@code OrphanedRuleChecker}'s
     * per-rule handling already established.
     */
    private Flux<IdpRelation> clientRelations(String token) {
        return client.get()
                .uri("/admin/realms/{realm}/clients", realm)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToFlux(KeycloakClient.class)
                .filter(c -> !isSystemClient(c.clientId))
                .flatMap(c -> serviceAccountRoleAssignments(token, c.id).onErrorResume(ex -> Flux.empty()));
    }

    private Flux<IdpRelation> serviceAccountRoleAssignments(String token, String clientUuid) {
        return client.get()
                .uri("/admin/realms/{realm}/clients/{clientUuid}/service-account-user", realm, clientUuid)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .bodyToMono(KeycloakUser.class)
                .flatMapMany(serviceAccountUser -> client.get()
                        .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", realm, serviceAccountUser.id)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToFlux(KeycloakRole.class)
                        // subject is the CLIENT's own external_id, not the service-account user's —
                        // idp_identities never caches service-account users as USER rows (Keycloak's
                        // own /users endpoint excludes them, confirmed live in the ADR-014 session).
                        .map(r -> new IdpRelation(clientUuid, r.id, RelationType.HAS_ROLE)));
    }

    /**
     * Keycloak's every-realm built-in clients (ADR-016) — never a legitimate
     * policy-rule {@code source} or business actor, so excluded from the
     * cache entirely rather than synced-then-ignored. Exact-match set (not
     * just the two prefixes) because the bare {@code "account"}/{@code
     * "broker"} client ids don't themselves start with {@code "account-"}/
     * {@code "broker-"} — only their satellite clients
     * ({@code account-console}) do.
     */
    private static final Set<String> SYSTEM_CLIENT_IDS =
            Set.of("account", "broker", "realm-management", "admin-cli", "security-admin-console");
    private static final List<String> SYSTEM_CLIENT_PREFIXES = List.of("account-", "broker-");

    // Package-private (not private) so KeycloakIdpAdapterTest can unit-test this
    // pure predicate directly — this adapter's HTTP calls stay proven only via
    // IdentitySyncIT against a real Keycloak (ADR-014's established precedent).
    static boolean isSystemClient(String clientId) {
        if (SYSTEM_CLIENT_IDS.contains(clientId)) return true;
        return SYSTEM_CLIENT_PREFIXES.stream().anyMatch(clientId::startsWith);
    }

    private <T> Flux<T> withToken(java.util.function.Function<String, Flux<T>> call) {
        return fetchToken().flatMapMany(call);
    }

    private Mono<String> fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        return client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .map(TokenResponse::accessToken);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KeycloakUser {
        public String id;
        public String username;
        public String firstName;
        public String lastName;

        String displayName() {
            String full = String.join(" ",
                    StringUtils.hasText(firstName) ? firstName : "",
                    StringUtils.hasText(lastName) ? lastName : "").trim();
            return full.isEmpty() ? username : full;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KeycloakGroup {
        public String id;
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KeycloakRole {
        public String id;
        public String name;
        public String description;

        String displayName() {
            return StringUtils.hasText(description) ? description : name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KeycloakClient {
        public String id;
        public String clientId;
        public String name;
        public String description;

        String displayName() {
            if (StringUtils.hasText(name)) return name;
            if (StringUtils.hasText(description)) return description;
            return clientId;
        }
    }
}
