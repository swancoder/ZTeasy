-- ============================================================
-- V10 — captured discovery payload for the APIM inventory (ADR-016 amendment)
-- ============================================================
-- AutoDiscoveryWorker's /v3/api-docs (REST) and tools/list (MCP) probes
-- previously only checked reachability and discarded the response body.
-- discovered_schema stores that body verbatim (the OpenAPI document for
-- REST, the JSON-RPC tools/list response for MCP) so the Admin Console can
-- render it on demand, without re-probing the target live on every view.
--
-- JSONB, not TEXT: lets Postgres validate/normalize the payload as real
-- JSON at write time (a malformed body simply isn't written — see
-- AutoDiscoveryWorker), and leaves room for a future indexed/queryable use
-- without a migration. Nullable: PENDING entries and any entry whose last
-- probe failed (WARNING/no successful discovery yet) have no captured
-- payload, and the historic 2xx-but-empty-body case can also leave it
-- unset rather than storing an invalid empty string.
-- ============================================================

ALTER TABLE inventory_services ADD COLUMN discovered_schema JSONB;

COMMENT ON COLUMN inventory_services.discovered_schema IS
    'Raw response body captured on a successful AutoDiscoveryWorker probe — the OpenAPI document (REST, /v3/api-docs) or JSON-RPC tools/list response (MCP). NULL until first successful discovery. Fetched on demand via GET /api/v1/admin/inventory/{id}/schema, deliberately excluded from the list/CRUD entity so the main registry view stays light (ADR-016 amendment).';
