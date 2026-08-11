package com.zte.gateway.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentitySyncService}.
 */
@ExtendWith(MockitoExtension.class)
class IdentitySyncServiceTest {

    @Mock IdpClient idpClient;
    @Mock IdpIdentityRepository repository;

    @Test
    void syncNow_upsertsEveryFetchedIdentityAcrossAllFourKinds() {
        when(idpClient.fetchUsers()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.USER, "u1", "zte-admin", "ZTE Admin")));
        when(idpClient.fetchGroups()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.GROUP, "g1", "ops-team", "ops-team")));
        when(idpClient.fetchRoles()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.ROLE, "r1", "ADMIN", "ADMIN"),
                IdpIdentity.fetched(IdentityType.ROLE, "r2", "USER", "USER")));
        when(idpClient.fetchClients()).thenReturn(Flux.just(
                IdpIdentity.fetched(IdentityType.CLIENT, "c1", "agent-a", "MCP Agent A")));
        when(repository.upsert(any(), any(), any(), any())).thenReturn(Mono.empty());

        IdentitySyncService service = new IdentitySyncService(idpClient, repository);

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

        IdentitySyncService service = new IdentitySyncService(idpClient, repository);

        StepVerifier.create(service.syncNow())
                .expectNext(0)
                .verifyComplete();
    }
}
