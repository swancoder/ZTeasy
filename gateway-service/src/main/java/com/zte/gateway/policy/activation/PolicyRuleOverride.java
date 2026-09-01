package com.zte.gateway.policy.activation;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One rule's activation override (Stage 31, ADR-031). Only rules an operator
 * has touched have a row; absence means the YAML default (enabled).
 *
 * <p>{@code ruleId} is the primary key and comes from the YAML document, so
 * this entity manages its own persistence via the repository's upsert rather
 * than Spring Data's null-id insert heuristic (SPECS §8) — the id is never
 * DB-generated here.
 */
@Table("policy_rule_overrides")
public record PolicyRuleOverride(
        @Id @Column("rule_id") String ruleId,
        boolean enabled,
        @Column("updated_by") String updatedBy,
        @Column("updated_at") Instant updatedAt
) {}
