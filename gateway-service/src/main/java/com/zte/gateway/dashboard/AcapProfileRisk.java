package com.zte.gateway.dashboard;

/** One ACAP profile's risk posture (Stage 29, ADR-029). */
public record AcapProfileRisk(
        String agentId,
        String displayName,
        String euAiActClass,
        Integer internalTier,
        String reauthDue,
        boolean overdue
) {}
