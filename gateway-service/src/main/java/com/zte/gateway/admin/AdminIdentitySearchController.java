package com.zte.gateway.admin;

import com.zte.gateway.identity.IdentityType;
import com.zte.gateway.identity.IdpIdentity;
import com.zte.gateway.identity.IdpIdentityRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Admin Console API (ADR-014): searches the local {@code idp_identities}
 * cache — backs the React SPA's Identities tab (no filters = list
 * everything) and, indirectly (via a plain fetch, no server-side join), the
 * Policies tab's client-side orphaned-rule highlighting.
 *
 * <p>Security: covered by the same {@code u2s-admin-console-api} YAML rule
 * and {@link AdminAuthorizationFilter} as {@link AdminPolicyController} —
 * that filter's path check is {@code /api/v1/admin/**} generically, so no
 * new security wiring is needed for this new sub-path.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminIdentitySearchController {

    private final IdpIdentityRepository repository;

    AdminIdentitySearchController(IdpIdentityRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/identities/search")
    public Flux<IdpIdentity> search(@RequestParam(required = false) String type,
                                     @RequestParam(required = false, defaultValue = "") String q) {
        if (StringUtils.hasText(type)) {
            IdentityType parsed;
            try {
                parsed = IdentityType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return Flux.empty();
            }
            return repository.searchByTypeAndName(parsed.name(), q);
        }
        return StringUtils.hasText(q)
                ? repository.findByNameContainingIgnoreCaseOrderByTypeAscNameAsc(q)
                : repository.findAllByOrderByTypeAscNameAsc();
    }
}
