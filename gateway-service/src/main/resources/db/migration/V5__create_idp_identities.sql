-- ============================================================
-- V5 — Local IdP identity cache (ADR-014)
-- ============================================================
-- Synced periodically from the configured IdP (Keycloak by default —
-- see com.zte.gateway.identity.IdpClient) into this table, so
-- users2service policy matching (URN sources: user:<name>, group:<name>,
-- role:<name>) never calls out to the IdP on a per-request basis.
-- ============================================================

CREATE TABLE idp_identities (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type         VARCHAR(10)  NOT NULL CHECK (type IN ('USER', 'GROUP', 'ROLE')),
    external_id  VARCHAR(255) NOT NULL,
    name         VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    last_synced  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (type, external_id)
);

CREATE INDEX idx_idp_identities_type_name ON idp_identities (type, name);

COMMENT ON TABLE idp_identities IS
    'Local cache of IdP identity metadata (users/groups/roles) — no secrets, no passwords, just id/type/name for URN-based policy matching and orphaned-rule validation (ADR-014).';
