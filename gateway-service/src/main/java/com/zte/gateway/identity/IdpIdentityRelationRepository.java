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
}
