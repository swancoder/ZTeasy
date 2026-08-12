package com.zte.gateway.admin;

import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyReloadResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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

    AdminPolicyController(PolicyDefinitionStore policyDefinitionStore) {
        this.policyDefinitionStore = policyDefinitionStore;
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
}
