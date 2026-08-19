package com.zte.gateway.mcp.acap;

/** ACAP's {@code risk} block (Stage 6, ADR-022) — display-only, no enforcement. */
public record AcapRisk(String euAiActClass, Integer internalTier) {
}
