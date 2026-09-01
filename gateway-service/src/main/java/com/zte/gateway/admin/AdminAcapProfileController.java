package com.zte.gateway.admin;

import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileReloadResult;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapThreshold;
import com.zte.gateway.mcp.acap.AcapThresholdTracker;
import com.zte.gateway.mcp.acap.lifecycle.AcapLifecycleState;
import com.zte.gateway.mcp.acap.lifecycle.AcapLifecycleStore;
import com.zte.gateway.mcp.acap.lifecycle.AcapReauthorization;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Set<String> SETTABLE_STATUSES =
            Set.of(AcapLifecycleState.ACTIVE, AcapLifecycleState.SUSPENDED, AcapLifecycleState.RETIRED);

    private final AcapProfileStore acapProfileStore;
    private final AcapThresholdTracker thresholdTracker;
    private final AcapLifecycleStore lifecycleStore;

    AdminAcapProfileController(AcapProfileStore acapProfileStore, AcapThresholdTracker thresholdTracker,
                                AcapLifecycleStore lifecycleStore) {
        this.acapProfileStore = acapProfileStore;
        this.thresholdTracker = thresholdTracker;
        this.lifecycleStore = lifecycleStore;
    }

    @GetMapping
    public List<AcapProfileView> list() {
        return acapProfileStore.all().stream().map(this::toView).toList();
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reload() {
        return acapProfileStore.reload().map(AcapProfileReloadResult::toResponseEntity);
    }

    /**
     * Lifecycle transition (Stage 32, ADR-032): SUSPENDED/RETIRED deny every
     * call; ACTIVE restores normal evaluation. {@code 404} for agents without
     * a loaded profile — lifecycle is a property of a governed agent.
     */
    @PutMapping("/{agentId}/status")
    public Mono<ResponseEntity<Object>> setStatus(@PathVariable String agentId,
                                                   @RequestBody StatusRequest body,
                                                   @AuthenticationPrincipal Jwt jwt) {
        if (acapProfileStore.find(agentId).isEmpty()) {
            return Mono.just(profileNotFound(agentId));
        }
        String status = body.status() == null ? "" : body.status().toUpperCase();
        if (!SETTABLE_STATUSES.contains(status)) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be one of " + SETTABLE_STATUSES)));
        }
        return lifecycleStore.setStatus(agentId, status, username(jwt))
                .map(saved -> ResponseEntity.ok((Object) saved));
    }

    /** Records a re-authorization (who/when/next due/note) and moves the effective due date (Stage 32, ADR-032). */
    @PostMapping("/{agentId}/reauthorize")
    public Mono<ResponseEntity<Object>> reauthorize(@PathVariable String agentId,
                                                     @RequestBody ReauthorizeRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        if (acapProfileStore.find(agentId).isEmpty()) {
            return Mono.just(profileNotFound(agentId));
        }
        LocalDate nextDue;
        try {
            nextDue = LocalDate.parse(body.nextDue());
        } catch (Exception e) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "nextDue must be an ISO date (yyyy-MM-dd)")));
        }
        return lifecycleStore.reauthorize(agentId, nextDue, body.note(), username(jwt))
                .map(saved -> ResponseEntity.ok((Object) saved));
    }

    @GetMapping("/{agentId}/reauthorizations")
    public Mono<ResponseEntity<Object>> history(@PathVariable String agentId) {
        if (acapProfileStore.find(agentId).isEmpty()) {
            return Mono.just(profileNotFound(agentId));
        }
        return lifecycleStore.history(agentId).map(h -> ResponseEntity.ok((Object) h));
    }

    private ResponseEntity<Object> profileNotFound(String agentId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "No ACAP profile loaded for agent '" + agentId + "'"));
    }

    private String username(Jwt jwt) {
        if (jwt == null) return "unknown";
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null ? preferred : jwt.getSubject();
    }

    private AcapProfileView toView(AcapProfile profile) {
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (AcapThreshold threshold : profile.thresholds()) {
            usage.put(threshold.metric(), thresholdTracker.currentCount(profile.agentId(), threshold.metric()));
        }
        return new AcapProfileView(profile, usage,
                lifecycleStore.status(profile.agentId()),
                lifecycleStore.effectiveReauthDue(profile).map(Object::toString).orElse(null),
                lifecycleStore.isReauthOverdue(profile));
    }

    /**
     * {@code profile} + current-day threshold usage + lifecycle state
     * (Stage 32, ADR-032): {@code status}, the EFFECTIVE re-authorization
     * due date (DB override or the file's), and whether it is overdue.
     */
    record AcapProfileView(AcapProfile profile, Map<String, Integer> currentThresholdUsage,
                            String lifecycleStatus, String effectiveReauthDue, boolean reauthOverdue) {
    }

    record StatusRequest(String status) {}

    record ReauthorizeRequest(String nextDue, String note) {}
}
