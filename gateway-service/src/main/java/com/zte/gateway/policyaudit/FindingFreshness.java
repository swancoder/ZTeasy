package com.zte.gateway.policyaudit;

/**
 * Whether a finding still applies to the CURRENT policy document (Stage 31,
 * ADR-031). Computed at read time from the rule hashes captured at run time
 * — see {@link FreshnessEvaluator}.
 */
public enum FindingFreshness {
    /** Every referenced rule is unchanged since the run — the finding stands. */
    CURRENT,
    /** A referenced rule was edited since the run — re-audit before acting. */
    RULE_CHANGED,
    /** Referenced rules were removed, or the suggested disable is done — nothing left to act on. */
    ADDRESSED
}
