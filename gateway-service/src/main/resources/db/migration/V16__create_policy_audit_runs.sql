-- Stage 31 (ADR-031): persisted AI policy-audit runs. findings_json is the
-- structured findings list; rule_hashes_json maps every referenced rule id
-- to a content hash taken AT RUN TIME, which is what lets the UI say
-- honestly whether a finding still applies (rule unchanged), went stale
-- (rule edited), or was addressed (rule removed/disabled) — computed against
-- the live document at read time, never stored as a mutable status.
-- TEXT rather than JSONB: nothing filters on the internals, matching the
-- request_logs.message convention (SPECS §8).
CREATE TABLE IF NOT EXISTS policy_audit_runs (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    requested_by     VARCHAR(128),
    model            VARCHAR(128),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('COMPLETED','PARSE_ERROR','FAILED')),
    raw_report       TEXT,
    findings_json    TEXT,
    rule_hashes_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_policy_audit_runs_ts ON policy_audit_runs (timestamp DESC);
