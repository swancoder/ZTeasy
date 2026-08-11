package com.zte.gateway.identity;

import reactor.core.publisher.Flux;

/**
 * Adapter boundary for a source-of-truth identity provider (ADR-014).
 *
 * <p>Keycloak is the only implementation today ({@link KeycloakIdpAdapter}),
 * but the interface carries no Keycloak-specific shape — a future Azure
 * Entra ID or AWS IAM adapter implements this same contract. Returned
 * {@link IdpIdentity} instances have {@code id}/{@code lastSynced} unset;
 * {@link IdentitySyncService} assigns those via
 * {@link IdpIdentityRepository#upsert}.
 */
public interface IdpClient {

    Flux<IdpIdentity> fetchUsers();

    Flux<IdpIdentity> fetchGroups();

    Flux<IdpIdentity> fetchRoles();

    /**
     * OIDC clients (machine identities — ADR-015), e.g. {@code agent-a},
     * {@code zte-gateway}, and any of the realm's built-in clients. Fetches
     * <em>all</em> clients, not just {@code serviceAccountsEnabled} ones — an
     * accepted MVP simplification (ADR-015 Self-Critique), not a filtered
     * "actors only" view.
     */
    Flux<IdpIdentity> fetchClients();
}
