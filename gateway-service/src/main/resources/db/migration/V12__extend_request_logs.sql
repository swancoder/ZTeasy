-- ============================================================
-- V12 — richer request_logs columns for S2S/OBO/MCP audit (ADR-017)
-- ============================================================
-- request_logs already exists (V4, ADR-013) — this ALTERs it rather than
-- creating a second table, and reuses its existing `path`/`tool_name`
-- columns instead of adding duplicate `target_path`/`mcp_tools` columns
-- with the same meaning (see ADR-017).
-- ============================================================

ALTER TABLE request_logs
    ADD COLUMN initiator_client  VARCHAR(128),
    ADD COLUMN original_user_obo VARCHAR(128),
    ADD COLUMN target_service    VARCHAR(255),
    ADD COLUMN http_method       VARCHAR(10),
    ADD COLUMN decision_effect   VARCHAR(10);

COMMENT ON COLUMN request_logs.initiator_client IS
    'The calling client/service identity (JWT azp claim) for a machine-to-machine request — e.g. an MCP agent or a service authenticating to call another service through the gateway. NULL for a plain interactive-user request (ADR-017).';
COMMENT ON COLUMN request_logs.original_user_obo IS
    'The JWT subject that reached the gateway — the same identity the gateway embeds into the On-Behalf-Of token it mints for downstream propagation (ADR-017).';
COMMENT ON COLUMN request_logs.target_service IS
    'RequestTargetResolver-derived target service name (e.g. "service-a") — the same name inventory_services.name must match for passive telemetry (ADR-016) (ADR-017).';
COMMENT ON COLUMN request_logs.http_method IS
    'HTTP method of the request (ADR-017).';
COMMENT ON COLUMN request_logs.decision_effect IS
    'ALLOW/DENY/ERROR, derived from the final status code — a coarse signal, not per-policy-rule provenance (ADR-017 Self-Criticism: cannot distinguish a ZTE-layer DENY from a downstream service''s own 403).';
