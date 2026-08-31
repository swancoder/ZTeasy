package com.zte.gateway.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentitySyncService}.
 */
@ExtendWith(MockitoExtension.class)
class IdentitySyncServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID ROLE_1_ID = UUID.randomUUID();
    private static final UUID ROLE_2_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    @Mock IdpClient idpClient;
    @Mock IdpIdentityRepository repository;
    @Mock IdpIdentityRelationRepository relationRepository;

    private IdentitySyncService service;

    @BeforeEach
    void setUp() {
        when(idpClient.fetchUsers()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.USER, "u1", "zte-admin", "ZTE Admin")));
        when(idpClient.fetchGroups()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.GROUP, "g1", "ops-team", "ops-team")));
        when(idpClient.fetchRoles()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.ROLE, "r1", "ADMIN", "ADMIN"),
                IdpIdentity.fetched(IdentityType.ROLE, "r2", "USER", "USER")));
        when(idpClient.fetchClients()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.CLIENT, "c1", "agent-a", "MCP Agent A")));

        lenient().when(repository.upsert(eq("USER"), eq("u1"), any(), any())).thenReturn(Mono.just(USER_ID));
        lenient().when(repository.upsert(eq("GROUP"), eq("g1"), any(), any())).thenReturn(Mono.just(GROUP_ID));
        lenient().when(repository.upsert(eq("ROLE"), eq("r1"), any(), any())).thenReturn(Mono.just(ROLE_1_ID));
        lenient().when(repository.upsert(eq("ROLE"), eq("r2"), any(), any())).thenReturn(Mono.just(ROLE_2_ID));
        lenient().when(repository.upsert(eq("CLIENT"), eq("c1"), any(), any())).thenReturn(Mono.just(CLIENT_ID));

        // Stage 30: every sync now reconciles away identities the IdP no longer
        // reports, so the repository is asked to delete-missing per type.
        lenient().when(repository.deleteMissing(any(), any())).thenReturn(Flux.empty());

        lenient().when(idpClient.fetchRelations()).thenReturn(Flux.empty());
        lenient().when(relationRepository.upsert(any(), any(), any())).thenReturn(Mono.empty());

        service = new IdentitySyncService(idpClient, repository, relationRepository);
    }

    @Test
    void syncNow_upsertsEveryFetchedIdentityAcrossAllFourKinds() {
        StepVerifier.create(service.syncNow())
                .expectNext(5)
                .verifyComplete();

        verify(repository).upsert("USER", "u1", "zte-admin", "ZTE Admin");
        verify(repository).upsert("GROUP", "g1", "ops-team", "ops-team");
        verify(repository).upsert("ROLE", "r1", "ADMIN", "ADMIN");
        verify(repository).upsert("ROLE", "r2", "USER", "USER");
        verify(repository).upsert("CLIENT", "c1", "agent-a", "MCP Agent A");
    }

    @Test
    void syncNow_noIdentitiesFetched_returnsZero() {
        when(idpClient.fetchUsers()).thenReturn(Flux.empty());
        when(idpClient.fetchGroups()).thenReturn(Flux.empty());
        when(idpClient.fetchRoles()).thenReturn(Flux.empty());
        when(idpClient.fetchClients()).thenReturn(Flux.empty());

        StepVerifier.create(service.syncNow())
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void syncNow_resolvableRelation_upsertedWithInternalIds() {
        when(idpClient.fetchRelations()).thenReturn(Flux.just(
                new IdpRelation("u1", "g1", RelationType.MEMBER_OF),
                new IdpRelation("c1", "r1", RelationType.HAS_ROLE)));

        StepVerifier.create(service.syncNow())
                .expectNext(5)
                .verifyComplete();

        verify(relationRepository).upsert(USER_ID, GROUP_ID, "MEMBER_OF");
        verify(relationRepository).upsert(CLIENT_ID, ROLE_1_ID, "HAS_ROLE");
    }

    @Test
    void syncNow_unresolvableRelation_skippedNotUpserted() {
        when(idpClient.fetchRelations()).thenReturn(Flux.just(
                new IdpRelation("u1", "nonexistent-group", RelationType.MEMBER_OF)));

        StepVerifier.create(service.syncNow())
                .expectNext(5)
                .verifyComplete();

        verify(relationRepository, never()).upsert(any(), any(), any());
    }

    /**
     * Stage 30: the cache used to be append-only, so a realm re-import (which
     * gives every identity a new external id) left the old rows behind — seen
     * live as four copies of zte-admin on demo.zteasy.tech.
     */
    @Test
    void syncNow_reconcilesEachTypeAgainstWhatTheIdpStillReports() {
        StepVerifier.create(service.syncNow())
                .expectNext(5)
                .verifyComplete();

        verify(repository).deleteMissing("USER", List.of("u1"));
        verify(repository).deleteMissing("GROUP", List.of("g1"));
        verify(repository).deleteMissing("CLIENT", List.of("c1"));
        // Both roles were fetched in the same cycle, so both are kept.
        ArgumentCaptor<Collection<String>> roleIds = ArgumentCaptor.forClass(Collection.class);
        verify(repository).deleteMissing(eq("ROLE"), roleIds.capture());
        assertThat(roleIds.getValue()).containsExactlyInAnyOrder("r1", "r2");
    }

    /**
     * A type whose fetch came back empty is skipped, never reconciled: an IdP
     * call that fails or returns nothing must not be read as "there are no
     * users" and empty the cache.
     */
    @Test
    void syncNow_emptyFetchForAType_doesNotDeleteThatTypesCache() {
        when(idpClient.fetchGroups()).thenReturn(Flux.empty());

        StepVerifier.create(service.syncNow())
                .expectNext(4)
                .verifyComplete();

        verify(repository, never()).deleteMissing(eq("GROUP"), any());
        verify(repository).deleteMissing("USER", List.of("u1"));
    }
}
