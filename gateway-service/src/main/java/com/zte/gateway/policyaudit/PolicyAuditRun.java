package com.zte.gateway.policyaudit;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted AI policy-audit run (Stage 31, ADR-031). Findings and the
 * per-rule content hashes are JSON-in-TEXT ({@code request_logs.message}
 * convention, SPECS §8): nothing filters on their internals, and freshness
 * is always computed against the live document at read time — never stored,
 * so it cannot go stale itself.
 *
 * <p>{@code id} left {@code null} on construction — DB-generated (SPECS §8).
 */
@Table("policy_audit_runs")
public record PolicyAuditRun(
        @Id UUID id,
        Instant timestamp,
        @Column("requested_by") String requestedBy,
        String model,
        String status,
        @Column("raw_report") String rawReport,
        @Column("findings_json") String findingsJson,
        @Column("rule_hashes_json") String ruleHashesJson
) {}
