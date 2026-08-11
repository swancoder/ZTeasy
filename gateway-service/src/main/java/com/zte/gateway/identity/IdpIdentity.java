package com.zte.gateway.identity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single cached IdP identity (ADR-014) — no secrets, no passwords, just
 * enough to resolve a policy rule's URN source (id/type/name) and drive the
 * Admin Console's identity search.
 *
 * <p>{@code id} is left {@code null} on construction for identities fetched
 * fresh from {@link IdpClient} — {@link IdpIdentityRepository#upsert}
 * assigns/reuses the DB-generated id via {@code ON CONFLICT (type,
 * external_id)}, the same "let the DB decide" convention {@code RequestLog}
 * (ADR-013) already established.
 */
@Table("idp_identities")
public record IdpIdentity(
        @Id                    UUID         id,
                               IdentityType type,
        @Column("external_id") String       externalId,
                               String       name,
        @Column("display_name") String      displayName,
        @Column("last_synced") Instant      lastSynced
) {

    /** A freshly fetched identity, not yet persisted — {@code id}/{@code lastSynced} are assigned on upsert. */
    public static IdpIdentity fetched(IdentityType type, String externalId, String name, String displayName) {
        return new IdpIdentity(null, type, externalId, name, displayName, null);
    }
}
