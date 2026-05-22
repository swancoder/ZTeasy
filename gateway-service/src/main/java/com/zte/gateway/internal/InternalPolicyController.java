package com.zte.gateway.internal;

import com.zte.gateway.policy.AccessPolicy;
import com.zte.gateway.policy.AccessPolicyRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Internal REST endpoint that exposes all access policies for consumption
 * by ZTE internal agents (e.g., the Policy Auditor in {@code zt-agents}).
 *
 * <p>Security: restricted to {@code /api/v1/internal/**} which is served
 * without JWT validation by {@link InternalSecurityConfig}. Access is
 * enforced at the network layer (Docker bridge — not exposed via public routing).
 *
 * <p>This controller queries the DB directly (bypassing the 5-min policy cache
 * in {@code PolicyService}) so that audit agents always see the latest state.
 *
 * <p>Production upgrade: add Keycloak client_credentials grant for zt-agents,
 * create an INTERNAL role, add a DB policy row, and remove the permitAll override.
 */
@RestController
@RequestMapping("/api/v1/internal")
class InternalPolicyController {

    private final AccessPolicyRepository repository;

    InternalPolicyController(AccessPolicyRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/policies")
    public Flux<AccessPolicy> listAllPolicies() {
        return repository.findAll();
    }
}
