-- Stage 29 (ADR-029): real token metering behind the executive dashboard's
-- "LLM spend" tile. Deliberately a separate table from request_logs: a gate
-- decision and an LLM call are different events (one tool call may cost
-- nothing, one agent turn may cost several calls), and request_logs is on the
-- hot request path — this one is written out-of-band by whoever actually
-- spent the tokens.
--
-- Cost is stored, not derived at read time: prices change, and a report for
-- last month must keep the price that was in effect then.
CREATE TABLE IF NOT EXISTS llm_usage (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    agent_id       VARCHAR(128) NOT NULL,
    model          VARCHAR(128) NOT NULL,
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    -- Micro-euros, integer: floating point money in a dashboard that sums
    -- thousands of rows drifts visibly. Divide by 1e6 for display.
    cost_micros    BIGINT       NOT NULL DEFAULT 0,
    -- Free-text label for what the spend was for (e.g. "policy-audit"),
    -- following the existing convention of extending a text field rather
    -- than adding a column per dimension (SPECS §8).
    purpose        VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_timestamp ON llm_usage (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_llm_usage_agent     ON llm_usage (agent_id);
