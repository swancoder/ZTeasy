package com.zte.gateway.internal;

import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyRule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal REST endpoint that exposes the active {@code users2service} YAML
 * policy rules for consumption by ZTE internal agents (e.g., the Policy
 * Auditor in {@code zt-agents}).
 *
 * <p>Security: restricted to {@code /api/v1/internal/**} which is served
 * without JWT validation by {@link InternalSecurityConfig}. Access is
 * enforced at the network layer (Docker bridge — not exposed via public routing).
 *
 * <p>As of ADR-012, {@code users2service} is YAML-only (no more DB-backed
 * {@code access_policies}), so this reads {@link PolicyDefinitionStore}'s
 * in-memory snapshot directly — zero I/O, always current (including after a
 * {@code POST /api/v1/internal/policies/reload}).
 *
 * <p>Production upgrade: add Keycloak client_credentials grant for zt-agents,
 * create an INTERNAL role, add a YAML service2service rule, and remove the
 * permitAll override.
 */
@RestController
@RequestMapping("/api/v1/internal")
class InternalPolicyController {

    private final PolicyDefinitionStore policyDefinitionStore;

    InternalPolicyController(PolicyDefinitionStore policyDefinitionStore) {
        this.policyDefinitionStore = policyDefinitionStore;
    }

    @GetMapping("/policies")
    public List<PolicyRule> listAllPolicies() {
        return policyDefinitionStore.current().users2service();
    }
}
