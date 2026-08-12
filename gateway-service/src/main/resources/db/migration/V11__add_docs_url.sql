-- ============================================================
-- V11 — explicit docs_url override for REST discovery (ADR-016 amendment)
-- ============================================================
-- AutoDiscoveryWorker always probed {base_url}/v3/api-docs for REST
-- targets. docs_url lets an operator point discovery at an OpenAPI
-- document that doesn't live at that conventional path (or at a
-- different host entirely) — a full absolute URL, not a path suffix.
-- Nullable: unset means "keep using the {base_url}/v3/api-docs
-- convention", the same behavior every existing entry already has.
--
-- VARCHAR(512), matching base_url/management_url's sizing (not the
-- VARCHAR(255) originally suggested) — no reason for a URL override
-- column to be more size-constrained than the URLs it's an alternative
-- to.
-- ============================================================

ALTER TABLE inventory_services ADD COLUMN docs_url VARCHAR(512);

COMMENT ON COLUMN inventory_services.docs_url IS
    'Optional absolute URL AutoDiscoveryWorker probes instead of {base_url}/v3/api-docs for REST targets. NULL keeps the conventional path. Not used for MCP targets (their tools/list convention has no equivalent override) (ADR-016 amendment).';
