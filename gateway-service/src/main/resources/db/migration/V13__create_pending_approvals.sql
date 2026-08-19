-- ============================================================
-- V13 — pending_approvals: the 🟡 HOLD decision outcome (Stage 1, ADR-019)
-- ============================================================
-- A held MCP tool call, durable until a human approves or rejects it via
-- POST /api/v1/admin/approvals/{id}/approve|reject. DB-backed (not an
-- in-memory queue) because a held item may be reviewed well after it was
-- raised — the originating GET /sse session (mcp_session_manager, in-memory)
-- may well have closed by then; see PendingApproval's Javadoc.
-- ============================================================

CREATE TABLE pending_approvals (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       VARCHAR(64)  NOT NULL,
    agent_id         VARCHAR(128) NOT NULL,
    tool_name        VARCHAR(255) NOT NULL,
    rpc_id_json      VARCHAR(255) NOT NULL,
    arguments_json   TEXT,
    route_to         VARCHAR(128),
    reason           TEXT,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    decided_at       TIMESTAMPTZ,
    decided_by       VARCHAR(128),
    trace_id         VARCHAR(64),
    client_ip        VARCHAR(64),
    user_agent       VARCHAR(255),
    display_identity VARCHAR(128)
);

CREATE INDEX idx_pending_approvals_status ON pending_approvals (status, requested_at);

COMMENT ON TABLE pending_approvals IS
    'Tool calls parked for human review by an agentMcpToolHolds rule (Stage 1, ADR-019) — the demo''s 🟡 outcome. rpc_id_json/arguments_json are the original tools/call request''s id/arguments, compact-JSON serialized so the exact call can be reconstructed and forwarded unchanged on approval.';
