package com.zte.gateway.identity;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link IdpIdentityRelation} (ADR-016).
 */
@Repository
public interface IdpIdentityRelationRepository extends ReactiveCrudRepository<IdpIdentityRelation, UUID> {

    /**
     * Real Postgres {@code ON CONFLICT} upsert — same rationale as {@link
     * IdpIdentityRepository#upsert}: re-syncing the same relation every
     * cycle via {@code save()} would violate {@code UNIQUE (subject_id,
     * target_id, relation_type)} on the second sync. {@code relationType}
     * is a plain {@code String} (caller passes {@code RelationType.name()}),
     * matching {@code IdpIdentityRepository}'s established choice to avoid
     * derived-query/native-query <em>parameter</em> enum-binding doubt.
     */
    @Modifying
    @Query("""
            INSERT INTO idp_identity_relations (subject_id, target_id, relation_type, last_synced)
            VALUES (:subjectId, :targetId, :relationType, NOW())
            ON CONFLICT (subject_id, target_id, relation_type)
            DO UPDATE SET last_synced = NOW()
            """)
    Mono<Void> upsert(@Param("subjectId") UUID subjectId, @Param("targetId") UUID targetId,
                       @Param("relationType") String relationType);

    /** Every relation for a given subject identity — the read path {@code GET .../relations} uses. */
    Flux<IdpIdentityRelation> findBySubjectId(UUID subjectId);

    /**
     * Usernames holding a named realm role (ADR-035) — the reverse of
     * {@link #findBySubjectId}, which only walks subject → targets.
     *
     * <p>Answered from this local cache rather than from Keycloak, so notifying
     * someone costs no IdP round trip; the price is that a role granted since the
     * last {@code IdentitySyncService} cycle is not visible yet (15 min default).
     * Acceptable for addressing a message, and stated in ADR-035 rather than
     * assumed.
     */
    @Query("""
            SELECT subject.name
              FROM idp_identity_relations rel
              JOIN idp_identities subject ON subject.id = rel.subject_id
              JOIN idp_identities role    ON role.id    = rel.target_id
             WHERE rel.relation_type = 'HAS_ROLE'
               AND role.type = 'ROLE'
               AND role.name = :roleName
               AND subject.type = 'USER'
             ORDER BY subject.name
            """)
    Flux<String> findUsernamesWithRole(@Param("roleName") String roleName);
}
