package com.zte.gateway.mcp.acap;

import java.util.List;

/**
 * One {@code scope.read.allow[]} entry from an {@link AcapProfile} (Stage 3,
 * ADR-020) — the resource a {@code read_<resource>} tool call maps to
 * (`read_contacts` → `contacts`, see {@code AcapScopeEvaluator}), and the
 * fields permitted on it.
 *
 * @param resource matched against the tool name's {@code read_} suffix
 * @param fields   allowed field names for data-minimization checks; empty/null means no field restriction
 */
public record AcapReadGrant(String resource, List<String> fields) {

    public List<String> fields() {
        return fields == null ? List.of() : fields;
    }
}
