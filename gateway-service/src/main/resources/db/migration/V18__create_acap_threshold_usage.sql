-- Stage 32 (ADR-032): persisted daily threshold usage. The in-memory
-- counter (AcapThresholdTracker) stays authoritative on the request path
-- (evaluation is zero-I/O by design, ADR-009); this table is its async
-- write-behind and startup restore, so a gateway restart no longer resets
-- an agent's daily usage to zero.
CREATE TABLE IF NOT EXISTS acap_threshold_usage (
    agent_id  VARCHAR(128) NOT NULL,
    metric    VARCHAR(128) NOT NULL,
    day       DATE         NOT NULL,
    used      INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (agent_id, metric, day)
);
