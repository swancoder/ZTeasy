package com.zte.gateway.policyaudit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shape for a run (Stage 31, ADR-031) — findings carry computed freshness. */
public record PolicyAuditRunView(
        UUID id,
        Instant timestamp,
        String requestedBy,
        String model,
        String status,
        String rawReport,
        List<FindingView> findings
) {}
