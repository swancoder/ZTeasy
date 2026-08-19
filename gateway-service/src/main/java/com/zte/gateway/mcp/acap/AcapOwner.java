package com.zte.gateway.mcp.acap;

/** ACAP's {@code agent.owner} (Stage 6, ADR-022) — display-only, no enforcement. */
public record AcapOwner(String name, String email) {
}
