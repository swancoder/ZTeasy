package com.zte.gateway.admin;

import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileReloadResult;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapThreshold;
import com.zte.gateway.mcp.acap.AcapThresholdTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Console API (Stage 3, ADR-020; agent metadata/thresholds surfaced
 * Stage 6, ADR-022): exposes the loaded ACAP scope profiles, their current
 * threshold usage, and a reload trigger — the Admin Console's "Governance"
 * tab (ACAP Profiles section).
 *
 * <p>Security: same {@code u2s-admin-console-api} YAML rule and {@link
 * AdminAuthorizationFilter} as every other {@code /api/v1/admin/**}
 * controller. Deliberately no unauthenticated {@code /api/v1/internal/**}
 * counterpart (unlike {@code PolicyReloadController}) — ACAP profiles have
 * no {@code zt-agents}/ops-script consumer today; add one if that changes.
 */
@RestController
@RequestMapping("/api/v1/admin/acap-profiles")
class AdminAcapProfileController {

    private final AcapProfileStore acapProfileStore;
    private final AcapThresholdTracker thresholdTracker;

    AdminAcapProfileController(AcapProfileStore acapProfileStore, AcapThresholdTracker thresholdTracker) {
        this.acapProfileStore = acapProfileStore;
        this.thresholdTracker = thresholdTracker;
    }

    @GetMapping
    public List<AcapProfileView> list() {
        return acapProfileStore.all().stream().map(this::toView).toList();
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reload() {
        return acapProfileStore.reload().map(AcapProfileReloadResult::toResponseEntity);
    }

    private AcapProfileView toView(AcapProfile profile) {
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (AcapThreshold threshold : profile.thresholds()) {
            usage.put(threshold.metric(), thresholdTracker.currentCount(profile.agentId(), threshold.metric()));
        }
        return new AcapProfileView(profile, usage);
    }

    /** {@code profile} plus each of its thresholds' current-day usage count, keyed by metric name. */
    record AcapProfileView(AcapProfile profile, Map<String, Integer> currentThresholdUsage) {
    }
}
