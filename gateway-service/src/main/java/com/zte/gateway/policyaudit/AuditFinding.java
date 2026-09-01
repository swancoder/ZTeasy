package com.zte.gateway.policyaudit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One structured finding as stored (Stage 31, ADR-031) — mirrors zt-agents'
 * {@code AuditFinding} plus the id and acknowledgement this side assigns.
 * Mutable-by-copy: acknowledgement rewrites the run's findings JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditFinding(
        String id,
        String severity,
        String title,
        List<String> ruleIds,
        String recommendation,
        String suggestedAction,
        String suggestedYaml,
        String acknowledgedBy,
        String acknowledgedAt
) {
    public AuditFinding acknowledged(String by, String at) {
        return new AuditFinding(id, severity, title, ruleIds, recommendation, suggestedAction, suggestedYaml, by, at);
    }
}
