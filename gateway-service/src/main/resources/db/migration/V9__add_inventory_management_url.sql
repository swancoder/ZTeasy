-- ============================================================
-- V9 — optional management URL for APIM inventory entries (ADR-016 amendment)
-- ============================================================
-- HealthPollingService pinged base_url/actuator/health, but a REST
-- service's actuator endpoint doesn't have to live on the same
-- host:port as its API (service-a/service-b, for one, expose /v3/api-docs
-- on their mTLS API port and /actuator/health only on a separate plain
-- management port) — every such entry was permanently misreported DOWN.
-- management_url is nullable and optional: when unset, health polling
-- keeps pinging base_url exactly as before (no behavior change for
-- existing rows or any target whose actuator IS co-located with its API).
-- ============================================================

ALTER TABLE inventory_services ADD COLUMN management_url VARCHAR(512);

COMMENT ON COLUMN inventory_services.management_url IS
    'Optional base URL HealthPollingService pings /actuator/health against instead of base_url, for services whose management/actuator endpoint lives on a different host:port (e.g. a separate plain-HTTP management port alongside an mTLS API port). NULL falls back to base_url (ADR-016 amendment).';
