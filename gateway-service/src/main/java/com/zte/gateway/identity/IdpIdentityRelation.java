package com.zte.gateway.identity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single cached relation between two {@link IdpIdentity} rows (ADR-016)
 * — e.g. a user's group membership, or a user's/client's role assignment.
 * {@code subjectId}/{@code targetId} are {@code idp_identities.id} internal
 * PKs, not Keycloak external ids — resolved once per sync cycle, not
 * looked up per relation (see {@link IdpIdentityRepository#upsert}).
 */
@Table("idp_identity_relations")
public record IdpIdentityRelation(
        @Id                      UUID         id,
        @Column("subject_id")    UUID         subjectId,
        @Column("target_id")     UUID         targetId,
        @Column("relation_type") RelationType relationType,
        @Column("last_synced")   Instant      lastSynced
) {
}
