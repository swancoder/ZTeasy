-- ============================================================
-- V7 — IdP identity relations cache (ADR-016)
-- ============================================================
-- Many-to-many relationships between cached idp_identities rows —
-- User->Group (MEMBER_OF), User->Role and Client->Role (HAS_ROLE) — synced
-- alongside the identities themselves (IdentitySyncService), so
-- GET /api/v1/admin/identities/{id}/relations reads only this local
-- Postgres cache, never Keycloak, on every request.
--
-- subject_id/target_id reference idp_identities.id (the internal PK, not
-- the Keycloak external_id) — resolved once per sync cycle via
-- IdpIdentityRepository.upsert's RETURNING id, not looked up per relation.
-- ON DELETE CASCADE: defensive/future-proofing — idp_identities rows are
-- never deleted today (upsert-only), but a relation referencing a since-
-- removed identity would otherwise be an orphaned FK.
-- ============================================================

CREATE TABLE idp_identity_relations (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id    UUID        NOT NULL REFERENCES idp_identities(id) ON DELETE CASCADE,
    target_id     UUID        NOT NULL REFERENCES idp_identities(id) ON DELETE CASCADE,
    relation_type VARCHAR(20) NOT NULL CHECK (relation_type IN ('MEMBER_OF', 'HAS_ROLE')),
    last_synced   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (subject_id, target_id, relation_type)
);

CREATE INDEX idx_idp_identity_relations_subject ON idp_identity_relations (subject_id);

COMMENT ON TABLE idp_identity_relations IS
    'Local cache of IdP relational metadata (group membership, role assignment) for Users and Clients — read-only from the request path, written only during IdentitySyncService.syncNow() (ADR-016).';
