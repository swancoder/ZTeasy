package com.zte.gateway.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
}
