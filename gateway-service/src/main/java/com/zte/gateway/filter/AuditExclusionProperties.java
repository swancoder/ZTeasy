package com.zte.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code zte.audit.*} configuration — same shape/pattern as
 * {@code com.zte.gateway.policy.def.PolicyDefaultsProperties} (`zte.policy.*`).
 *
 * <p>Kept in {@code application.yml}, deliberately separate from
 * {@code zte-policies.yaml}: this is a flat list of path prefixes, not a
 * structured, hot-reloadable rule document — it doesn't need
 * {@code zte-policies.yaml}'s schema/validation machinery (ADR-011).
 */
@Component
@ConfigurationProperties(prefix = "zte.audit")
public class AuditExclusionProperties {

    /** Path prefixes excluded from the {@code request_logs} audit trail (ADR-013 amendment). */
    private List<String> excludedPathPrefixes = List.of();

    public List<String> getExcludedPathPrefixes() {
        return excludedPathPrefixes;
    }

    public void setExcludedPathPrefixes(List<String> excludedPathPrefixes) {
        this.excludedPathPrefixes = excludedPathPrefixes;
    }
}
