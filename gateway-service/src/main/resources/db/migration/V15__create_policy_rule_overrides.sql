-- Stage 31 (ADR-031): per-rule activation overlay. zte-policies.yaml stays
-- the sole source of rule DEFINITIONS (ADR-012); this table only records
-- which rules an operator has switched off (and by whom). A row whose
-- rule_id no longer exists in the document is inert — kept, not an error,
-- so a rule that is removed and later restored keeps its state.
CREATE TABLE IF NOT EXISTS policy_rule_overrides (
    rule_id     VARCHAR(255) PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL,
    updated_by  VARCHAR(128),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
