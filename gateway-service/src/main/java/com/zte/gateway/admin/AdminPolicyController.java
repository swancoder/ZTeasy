package com.zte.gateway.admin;

import com.zte.gateway.policy.activation.PolicyActivationStore;
import com.zte.gateway.policy.activation.PolicyRuleOverride;
import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyReloadResult;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.Map;

/**
 * Admin Console API (ADR-012): exposes the full active YAML policy document
 * and a reload trigger to the authenticated human operator, via the React
 * SPA served at {@code /admin/}.
 *
 * <p>Security: unlike {@code /api/v1/internal/**}, this stays behind the
 * default JWT-required chain from {@code auth-library}'s {@code SecurityConfig}
 * — there is no permitAll override for {@code /api/v1/admin/**}. Authorization
 * to ADMIN-only comes from the {@code u2s-admin-console-api} YAML
 * {@code users2service} rule in {@code zte-policies.yaml}, enforced by
 * {@link AdminAuthorizationFilter} — a plain {@code WebFilter}, not
 * {@code ZteAuthorizationFilter}'s Gateway {@code GlobalFilter}, since this
 * controller has no {@code InventoryRouteDefinitionLocator} route and {@code GlobalFilter}s
 * only run for routed requests (see that class's Javadoc for why).
 *
 * <p>Intentionally coexists with {@code InternalPolicyController}/
 * {@code PolicyReloadController} rather than replacing them — those serve
 * {@code zt-agents} and ops scripts under a network-perimeter trust model with
 * no user JWT; this serves the human operator under an ADMIN-JWT trust model.
 * Different audiences, both legitimate.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminPolicyController {

    private final PolicyDefinitionStore policyDefinitionStore;
    private final PolicyActivationStore activationStore;

    AdminPolicyController(PolicyDefinitionStore policyDefinitionStore, PolicyActivationStore activationStore) {
        this.policyDefinitionStore = policyDefinitionStore;
        this.activationStore = activationStore;
    }

    /** Returns all three rule categories — the operator-facing view of the active policy set. */
    @GetMapping("/policies")
    public PolicyDocument currentPolicies() {
        return policyDefinitionStore.current();
    }

    @PostMapping("/policies/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reload() {
        return policyDefinitionStore.reload().map(PolicyReloadResult::toResponseEntity);
    }

    /**
     * Stage 31 (ADR-031): the activation overlay, for the Policies tab to
     * merge onto the document above by rule id. Only touched rules have an
     * entry; absence means enabled.
     */
    @GetMapping("/policies/overrides")
    public Mono<List<PolicyRuleOverride>> overrides() {
        return activationStore.all();
    }

    /**
     * Toggles one rule's activation (Stage 31, ADR-031). {@code 404} for a
     * rule id the current document does not contain — an override may outlive
     * its rule, but it may not be created for one that never existed here.
     */
    @PutMapping("/policies/{ruleId}/enabled")
    public Mono<ResponseEntity<Object>> setEnabled(@PathVariable String ruleId,
                                                    @RequestBody ToggleRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        boolean known = policyDefinitionStore.current().allRules().stream()
                .map(PolicyRule::id)
                .anyMatch(ruleId::equals);
        if (!known) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No rule with id '" + ruleId + "' in the active policy document")));
        }
        String updatedBy = jwt == null ? "unknown"
                : jwt.getClaimAsString("preferred_username") != null
                        ? jwt.getClaimAsString("preferred_username") : jwt.getSubject();
        return activationStore.setEnabled(ruleId, body.enabled(), updatedBy)
                .map(saved -> ResponseEntity.ok((Object) saved));
    }

    record ToggleRequest(boolean enabled) {}
}
