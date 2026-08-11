-- ============================================================
-- V8 — API & Agent Management (APIM) inventory registry (ADR-016)
-- ============================================================
-- inventory_services: the operator-registered catalog of REST services and
-- MCP agents this gateway fronts (service-a/service-b already exist as
-- GatewayRouteConfig routes; this registry tracks them — and any future
-- onboarded agent/service — as first-class, health-monitored entities).
--
-- health_metrics: ONE current-state row per service (UNIQUE (service_id)),
-- not a time-series log — last_ping_ms/actuator_status are overwritten by
-- the periodic health-poll job, last_successful_call by real routed
-- traffic (RequestAuditFilter, fire-and-forget). A history table is a
-- possible future extension (see ADR), not built here.
-- ============================================================

CREATE TABLE inventory_services (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    target_type VARCHAR(10)  NOT NULL CHECK (target_type IN ('REST', 'MCP')),
    base_url    VARCHAR(512) NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('ACTIVE', 'WARNING', 'DOWN', 'PENDING')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE health_metrics (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id          UUID        NOT NULL UNIQUE REFERENCES inventory_services(id) ON DELETE CASCADE,
    last_ping_ms        INTEGER,
    actuator_status     VARCHAR(64),
    last_successful_call TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE inventory_services IS
    'APIM registry of REST services and MCP agents this gateway fronts — manually onboarded, auto-discovered (schema fetch), and health-monitored (ADR-016).';
COMMENT ON TABLE health_metrics IS
    'Current health snapshot per inventory_services row (one row each, not a history log) — updated by the periodic health-poll job (last_ping_ms/actuator_status) and by real routed traffic (last_successful_call, fire-and-forget from RequestAuditFilter) (ADR-016).';
