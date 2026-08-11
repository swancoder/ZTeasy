package com.zte.gateway.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Periodically pulls all identities from the configured {@link IdpClient} and
 * upserts them into the local {@code idp_identities} cache (ADR-014).
 *
 * <p>{@link #refresh()} is the {@code @Scheduled} entry point — Spring's own
 * {@code TaskScheduler} thread invokes it, never the Netty event loop, and
 * the reactive chain here never calls {@code .block()}, so "don't block the
 * reactive event loop" holds by construction. {@link #syncNow()} is the
 * shared Mono the manual admin endpoint also triggers.
 */
@Service
public class IdentitySyncService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-IDENTITY-SYNC");

    private final IdpClient idpClient;
    private final IdpIdentityRepository repository;

    public IdentitySyncService(IdpClient idpClient, IdpIdentityRepository repository) {
        this.idpClient = idpClient;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${zte.idp.sync-interval-ms:900000}")
    public void refresh() {
        syncNow().subscribe(
                count -> log.info("[ZTE-IDENTITY-SYNC] scheduled sync complete: {} identities upserted", count),
                ex -> log.error("[ZTE-IDENTITY-SYNC] scheduled sync failed", ex));
    }

    /** Fetches users/groups/roles/clients and upserts each; returns the total count upserted. */
    public Mono<Integer> syncNow() {
        return Flux.merge(idpClient.fetchUsers(), idpClient.fetchGroups(), idpClient.fetchRoles(),
                        idpClient.fetchClients())
                .flatMap(identity -> repository
                        .upsert(identity.type().name(), identity.externalId(), identity.name(), identity.displayName())
                        .thenReturn(1))
                .reduce(0, Integer::sum)
                .defaultIfEmpty(0);
    }
}
