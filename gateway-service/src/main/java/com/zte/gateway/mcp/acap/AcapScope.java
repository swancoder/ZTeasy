package com.zte.gateway.mcp.acap;

import java.util.List;

/**
 * The {@code scope} block of an {@link AcapProfile} (Stage 3, ADR-020) —
 * deliberately a simplified subset of the source ACAP schema's
 * {@code scope.read.allow}/{@code scope.write.allow}/{@code scope.write.deny}:
 * a single {@code writeAllowed} flag stands in for a full write-grant list
 * (this demo profile never grants any write; a future profile needing
 * partial write grants would need to extend this to a list, not just flip
 * the flag), and {@code scope.read.deny} isn't modeled at all — it's
 * derivable from {@code read} being an allow-list plus the top-level {@code
 * territory} check, so storing it separately would just be redundant data
 * that could drift from the allow-list it's supposed to mirror.
 *
 * @param read         {@code scope.read.allow[]} — empty/null means no resource is readable
 * @param writeAllowed simplified stand-in for {@code scope.write.allow} being non-empty
 */
public record AcapScope(List<AcapReadGrant> read, boolean writeAllowed) {

    public List<AcapReadGrant> read() {
        return read == null ? List.of() : read;
    }
}
