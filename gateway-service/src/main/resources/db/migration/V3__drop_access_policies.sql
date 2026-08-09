-- ============================================================
-- V3 — Retire the DB-backed access policy table
-- ============================================================
-- users2service is now enforced exclusively via the YAML policy engine
-- (zte-policies.yaml / PolicyDefinitionStore — see ADR-011, ADR-012).
-- This table's sole reader (PolicyService) has been deleted; nothing
-- queries it anymore.
-- ============================================================

DROP TABLE IF EXISTS access_policies;
