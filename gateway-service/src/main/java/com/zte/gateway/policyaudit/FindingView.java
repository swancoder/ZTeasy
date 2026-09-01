package com.zte.gateway.policyaudit;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** One finding plus its freshness against the live document (Stage 31, ADR-031). */
public record FindingView(
        @JsonUnwrapped AuditFinding finding,
        FindingFreshness freshness
) {}
