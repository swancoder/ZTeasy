package com.zte.gateway.mcp.acap;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

/** One day's usage of one metric by one agent (Stage 32, ADR-032). */
@Table("acap_threshold_usage")
public record AcapThresholdUsageRow(
        @Id @Column("agent_id") String agentId,
        String metric,
        LocalDate day,
        int used
) {}
