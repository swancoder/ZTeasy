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
     * {@code zte-gateway}. Every Keycloak-realm-builtin client
     * ({@code account}, {@code broker}, {@code realm-management},
     * {@code admin-cli}, {@code security-admin-console}, and their
     * satellite clients) is excluded (ADR-016) — never a legitimate policy
     * source or business actor. Still fetches every *other* client
     * regardless of {@code serviceAccountsEnabled} (ADR-015 Self-Critique).
     */
    Flux<IdpIdentity> fetchClients();

    /**
     * User→group membership, and user/client→realm-role assignment
     * (ADR-016) — for the identities {@link #fetchUsers()}/{@link
     * #fetchClients()} return only (group→role, if Keycloak groups had
     * default roles, is out of scope: no such rules exist in this realm and
     * the task's own relation types are {@code MEMBER_OF}/{@code HAS_ROLE}
     * on Actors only).
     */
    Flux<IdpRelation> fetchRelations();
}
