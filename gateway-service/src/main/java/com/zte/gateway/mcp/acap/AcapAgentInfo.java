package com.zte.gateway.mcp.acap;

/**
 * ACAP's {@code agent}/{@code assigned} metadata (Stage 6, ADR-022) —
 * {@code name}/{@code client}/{@code owner}/{@code deploymentDate}/{@code
 * reauthDue}, display-only in the Admin Console, no enforcement. Dates are
 * plain ISO-8601 strings ({@code yyyy-MM-dd}), not {@code LocalDate} —
 * avoids registering a Jackson time module on {@link AcapProfileFileLoader}'s
 * standalone {@code YAMLMapper} for a value nothing here computes with; the
 * Admin Console does the one comparison that matters ("is reauthDue in the
 * past") in JS.
 */
public record AcapAgentInfo(String name, String client, AcapOwner owner, String deploymentDate, String reauthDue) {
}
