package com.zte.gateway.identity;

/**
 * A single relation as fetched fresh from the IdP (ADR-016), keyed by the
 * IdP's own (external) identifiers — not yet resolved to
 * {@code idp_identities.id} internal PKs. {@link IdentitySyncService}
 * resolves {@code subjectExternalId}/{@code targetExternalId} against the
 * map it builds while upserting identities in the same sync cycle.
 *
 * <p>No subject/target type fields: {@link RelationType#MEMBER_OF}'s target
 * is always a {@link IdentityType#GROUP}; {@link RelationType#HAS_ROLE}'s is
 * always a {@link IdentityType#ROLE} — the relation type alone disambiguates.
 */
public record IdpRelation(String subjectExternalId, String targetExternalId, RelationType relationType) {
}
