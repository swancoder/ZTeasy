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
                .map(c -> IdpIdentity.fetched(IdentityType.CLIENT, c.id, c.clientId, c.displayName())));
    }

    private Flux<IdpIdentity> withToken(java.util.function.Function<String, Flux<IdpIdentity>> call) {
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
