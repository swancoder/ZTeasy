-- ============================================================
-- V4 — Async, R2DBC-backed request audit log (ADR-013)
-- ============================================================
-- Consolidates gateway_audit_log (V1, Stage 1) into this table instead of
-- leaving it orphaned: it was never read or written by any code since it
-- was created, and this table is the actual replacement for its stated
-- purpose ("immutable audit trail of authenticated requests").
-- ============================================================

DROP TABLE IF EXISTS gateway_audit_log;

CREATE TABLE request_logs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    trace_id     VARCHAR(64)  NOT NULL,
    client_ip    VARCHAR(64),
    user_agent   TEXT,
    process_id   VARCHAR(32),
    agent_id     VARCHAR(128),
    tool_name    VARCHAR(128),
    path         TEXT         NOT NULL,
    status_code  INTEGER,
    message      TEXT
);

CREATE INDEX idx_request_logs_timestamp ON request_logs (timestamp DESC);
CREATE INDEX idx_request_logs_trace_id  ON request_logs (trace_id);

COMMENT ON TABLE request_logs IS
    'Async, R2DBC-written audit trail of gateway requests (ADR-013) — every allowed/denied REST request, tagged with a distributed trace_id (X-Request-Id). agent_id/tool_name reserved for a future MCP-audit unification, always null for REST traffic today.';
