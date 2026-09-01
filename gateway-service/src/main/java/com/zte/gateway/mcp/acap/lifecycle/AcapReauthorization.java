package com.zte.gateway.mcp.acap.lifecycle;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One re-authorization decision (Stage 32, ADR-032) — append-only history. {@code id} DB-generated (SPECS §8). */
@Table("acap_reauthorizations")
public record AcapReauthorization(
        @Id UUID id,
        @Column("agent_id") String agentId,
        @Column("reauthorized_by") String reauthorizedBy,
        @Column("reauthorized_at") Instant reauthorizedAt,
        @Column("next_due") LocalDate nextDue,
        String note
) {}
