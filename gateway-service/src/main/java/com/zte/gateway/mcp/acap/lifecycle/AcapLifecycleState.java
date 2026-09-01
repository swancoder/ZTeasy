package com.zte.gateway.mcp.acap.lifecycle;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One agent's ACAP lifecycle state (Stage 32, ADR-032). Absence of a row
 * means ACTIVE with the profile file's own re-authorization date — exactly
 * the pre-stage behaviour, so existing deployments change nothing until an
 * operator acts.
 */
@Table("acap_profile_lifecycle")
public record AcapLifecycleState(
        @Id @Column("agent_id") String agentId,
        String status,
        @Column("reauth_due") LocalDate reauthDue,
        @Column("updated_by") String updatedBy,
        @Column("updated_at") Instant updatedAt
) {
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String RETIRED = "RETIRED";
}
