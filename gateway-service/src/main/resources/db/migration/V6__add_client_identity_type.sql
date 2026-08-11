-- ============================================================
-- V6 — Add CLIENT to idp_identities.type (ADR-015)
-- ============================================================
-- Machine identities (OIDC confidential clients — agent-a, agent-b,
-- zte-gateway itself, and any built-in Keycloak client) are now synced
-- into the same idp_identities cache as users/groups/roles (V5), so
-- service2service/agentMcpToolCalls YAML sources can be validated against
-- it too, the same way users2service already is.
--
-- `type` is VARCHAR(10)+CHECK, not a native Postgres enum (V5's own
-- reasoning still applies) — just widen the CHECK constraint. Postgres
-- auto-generated the constraint name idp_identities_type_check for the
-- single unnamed CHECK on that column in V5.
-- ============================================================

ALTER TABLE idp_identities DROP CONSTRAINT idp_identities_type_check;
ALTER TABLE idp_identities ADD CONSTRAINT idp_identities_type_check
    CHECK (type IN ('USER', 'GROUP', 'ROLE', 'CLIENT'));
