package com.zte.gateway.identity;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.Collection;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link IdpIdentity}.
 */
@Repository
public interface IdpIdentityRepository extends ReactiveCrudRepository<IdpIdentity, UUID> {

    /**
     * Real Postgres {@code ON CONFLICT} upsert — {@link ReactiveCrudRepository#save}
     * can't be used for re-syncing the same identity every cycle: its "null id
     * = new entity" heuristic (the convention {@code RequestLog} established,
     * ADR-013) would attempt a fresh INSERT every time and violate the
     * {@code UNIQUE (type, external_id)} constraint on the second sync. One
     * round trip, no read-then-write race.
     *
     * <p>{@code RETURNING id} (ADR-016) — {@link IdentitySyncService} needs
     * the (possibly pre-existing, possibly freshly generated) internal PK
     * back to resolve {@code idp_identity_relations}' subject/target
     * columns, without a second lookup query per identity. Deliberately
     * <strong>not</strong> {@code @Modifying}: that annotation switches
     * Spring Data R2DBC's result handling to "read the affected-row-count",
     * which is exactly wrong for an {@code INSERT ... RETURNING} whose
     * result set needs to be mapped to {@code UUID}, not counted.
     */
    @Query("""
            INSERT INTO idp_identities (type, external_id, name, display_name, last_synced)
            VALUES (:type, :externalId, :name, :displayName, NOW())
            ON CONFLICT (type, external_id)
            DO UPDATE SET name = EXCLUDED.name, display_name = EXCLUDED.display_name, last_synced = NOW()
            RETURNING id
            """)
    Mono<UUID> upsert(@Param("type") String type, @Param("externalId") String externalId,
                       @Param("name") String name, @Param("displayName") String displayName);

    /**
     * Orphaned-rule check — does an identity of this exact (type, name) exist?
     * {@code type} is a plain {@code String} (callers pass {@code IdentityType.name()}),
     * matching {@link #upsert}'s choice — deliberately sidesteps any doubt about
     * derived-query <em>parameter</em> binding for enum types in this R2DBC
     * version (entity field mapping, i.e. reading {@code type} back out into
     * {@link IdpIdentity#type()}, is the well-established, reliable direction;
     * this is the less-traveled one).
     */
    /**
     * Deletes cached identities the IdP no longer reports (Stage 30).
     *
     * <p>Without this, the cache only ever grew: an identity that is deleted
     * and recreated in Keycloak — which is exactly what a realm re-import
     * does, and the cloud deployment re-imports on every restart by design
     * (ADR-027) — comes back with a fresh {@code external_id}, so the upsert
     * inserts a second row and the old one lingers forever. Seen live on
     * demo.zteasy.tech: {@code zte-admin} and {@code zte-test-user} each
     * cached four times over. Named as a known gap in SPECS §10
     * ("no reconciliation for stale rows"); this closes it for identities.
     *
     * <p>Scoped by {@code type} so one entity kind's fetch failing can't
     * wipe another's: the caller passes the ids it actually just saw, per
     * type, and only that type is reconciled.
     *
     * <p>Not {@code @Modifying} — same reasoning as {@link #upsert}: this
     * returns the deleted rows' ids so the caller can log what it removed.
     */
    @Query("""
            DELETE FROM idp_identities
             WHERE type = :type
               AND external_id NOT IN (:keepExternalIds)
            RETURNING id
            """)
    Flux<UUID> deleteMissing(@Param("type") String type, @Param("keepExternalIds") Collection<String> keepExternalIds);

    @Query("SELECT EXISTS(SELECT 1 FROM idp_identities WHERE type = :type AND name = :name)")
    Mono<Boolean> existsByTypeAndName(@Param("type") String type, @Param("name") String name);

    /** Admin Console search — {@code type} filter + case-insensitive substring match. */
    @Query("SELECT * FROM idp_identities WHERE type = :type AND name ILIKE CONCAT('%', :q, '%') ORDER BY name")
    Flux<IdpIdentity> searchByTypeAndName(@Param("type") String type, @Param("q") String q);

    /** Admin Console search — no {@code type} filter. */
    Flux<IdpIdentity> findByNameContainingIgnoreCaseOrderByTypeAscNameAsc(String q);

    /** All cached identities, for the "list everything" no-filter case. */
    Flux<IdpIdentity> findAllByOrderByTypeAscNameAsc();
}
