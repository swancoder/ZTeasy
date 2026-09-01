-- Stage 32 (ADR-032): ACAP lifecycle overlay. Profile CONTENT (scope,
-- fields, limits) stays in the per-agent YAML files — what a data owner
-- signs must not mutate at a button click. This table carries only the
-- profile's lifecycle STATE: whether the agent may operate, and when its
-- re-authorization is due (overriding the file's display-only date once
-- the cycle is managed in-app). No row = ACTIVE with the file's date.
CREATE TABLE IF NOT EXISTS acap_profile_lifecycle (
    agent_id    VARCHAR(128) PRIMARY KEY,
    status      VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','SUSPENDED','RETIRED')),
    reauth_due  DATE,
    updated_by  VARCHAR(128),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Every re-authorization decision, forever — the compliance artifact
-- behind "when was this agent last reviewed, by whom".
CREATE TABLE IF NOT EXISTS acap_reauthorizations (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id         VARCHAR(128) NOT NULL,
    reauthorized_by  VARCHAR(128) NOT NULL,
    reauthorized_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    next_due         DATE         NOT NULL,
    note             TEXT
);
CREATE INDEX IF NOT EXISTS idx_acap_reauth_agent ON acap_reauthorizations (agent_id, reauthorized_at DESC);
