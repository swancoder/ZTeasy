package com.zte.gateway.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Periodically pulls all identities (and, since ADR-016, their relations —
 * group membership, role assignment) from the configured {@link IdpClient}
 * and upserts them into the local {@code idp_identities}/{@code
 * idp_identity_relations} cache (ADR-014).
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
    private final IdpIdentityRelationRepository relationRepository;

    public IdentitySyncService(IdpClient idpClient, IdpIdentityRepository repository,
                                IdpIdentityRelationRepository relationRepository) {
        this.idpClient = idpClient;
        this.repository = repository;
        this.relationRepository = relationRepository;
    }

    @Scheduled(fixedDelayString = "${zte.idp.sync-interval-ms:900000}")
    public void refresh() {
        syncNow().subscribe(
                count -> log.info("[ZTE-IDENTITY-SYNC] scheduled sync complete: {} identities upserted", count),
                ex -> log.error("[ZTE-IDENTITY-SYNC] scheduled sync failed", ex));
    }

    /**
     * Fetches users/groups/roles/clients, upserts each, then fetches and
     * upserts their relations (resolved against the identities just
     * upserted in this same cycle — no extra lookup query per relation).
     * Returns the identity count only (unchanged contract with the admin
     * endpoint/UI); relation count is logged separately.
     */
    public Mono<Integer> syncNow() {
        return syncIdentities()
                .flatMap(resolution -> syncRelations(resolution)
                        .thenReturn(resolution.size()));
    }

    /** @return a map of every synced identity's Keycloak external id to its internal {@code idp_identities.id}. */
    private Mono<Map<String, UUID>> syncIdentities() {
        return Flux.merge(idpClient.fetchUsers(), idpClient.fetchGroups(), idpClient.fetchRoles(),
                        idpClient.fetchClients())
                .collectList()
                .flatMap(fetched -> reconcile(fetched).thenReturn(fetched))
                .flatMapMany(Flux::fromIterable)
                .flatMap(identity -> repository
                        .upsert(identity.type().name(), identity.externalId(), identity.name(), identity.displayName())
                        .map(internalId -> new SimpleEntry<>(identity.externalId(), internalId)))
                .collectMap(SimpleEntry::getKey, SimpleEntry::getValue);
    }

    /**
     * Removes cached identities the IdP no longer reports (Stage 30).
     *
     * <p>The cache used to be append-only, so an identity deleted and
     * recreated upstream — precisely what a realm re-import does, and the
     * cloud deployment re-imports on every restart (ADR-027) — accumulated a
     * new row per cycle under its new {@code external_id}. The Admin
     * Console then listed the same person several times, and every
     * orphaned-rule check ran against identities that no longer exist.
     *
     * <p>Deliberately per type, and deliberately skipped for a type that
     * came back empty: an IdP call that fails or returns nothing must never
     * be read as "the IdP has no users" and empty the cache. That makes this
     * safe against a partial fetch at the cost of leaving stale rows for one
     * more cycle in the (rare) case where a type legitimately becomes empty.
     */
    private Mono<Void> reconcile(List<IdpIdentity> fetched) {
        Map<String, List<String>> seenByType = fetched.stream().collect(Collectors.groupingBy(
                identity -> identity.type().name(),
                Collectors.mapping(IdpIdentity::externalId, Collectors.toList())));

        return Flux.fromIterable(seenByType.entrySet())
                .filter(entry -> !entry.getValue().isEmpty())
                .flatMap(entry -> repository.deleteMissing(entry.getKey(), entry.getValue())
                        .count()
                        .doOnNext(removed -> {
                            if (removed > 0) {
                                log.info("[ZTE-IDENTITY-SYNC] reconciled: removed {} stale {} identit{} no longer in the IdP",
                                        removed, entry.getKey(), removed == 1 ? "y" : "ies");
                            }
                        }))
                .then();
    }

    /**
     * Relations reference their subject/target by the same Keycloak
     * external id the just-upserted identities carry — resolved via the
     * in-memory map from {@link #syncIdentities()}, never a second DB
     * round trip per relation. A relation whose subject or target didn't
     * resolve (shouldn't happen — every relation names an entity this same
     * cycle's identity fetch also names) is skipped with a log line rather
     * than failing the sync.
     */
    private Mono<Void> syncRelations(Map<String, UUID> externalIdToInternalId) {
        // .thenReturn(1)/.reduce, not .count() — relationRepository.upsert returns Mono<Void>,
        // which never emits a value on completion, only onComplete; .count() on a Flux built
        // from Mono<Void>s would always be 0. The same Mono<Void>-has-no-value pitfall this
        // codebase has hit before (RequestAuditFilter/AdminAuthorizationFilter, ADR-012/013).
        return idpClient.fetchRelations()
                .flatMap(relation -> {
                    UUID subjectId = externalIdToInternalId.get(relation.subjectExternalId());
                    UUID targetId = externalIdToInternalId.get(relation.targetExternalId());
                    if (subjectId == null || targetId == null) {
                        log.warn("[ZTE-IDENTITY-SYNC] skipping unresolvable relation: {}", relation);
                        return Mono.<Integer>empty();
                    }
                    return relationRepository.upsert(subjectId, targetId, relation.relationType().name())
                            .thenReturn(1);
                })
                .reduce(0, Integer::sum)
                .defaultIfEmpty(0)
                .doOnNext(count -> log.info("[ZTE-IDENTITY-SYNC] scheduled sync complete: {} relations upserted", count))
                .then();
    }
}
