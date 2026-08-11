package com.zte.gateway.admin;

import com.zte.gateway.identity.IdentityType;
import com.zte.gateway.identity.IdpIdentity;
import com.zte.gateway.identity.IdpIdentityRelation;
import com.zte.gateway.identity.IdpIdentityRelationRepository;
import com.zte.gateway.identity.IdpIdentityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Admin Console API (ADR-016): the roles/groups related to a given actor
 * identity (a {@code USER} or {@code CLIENT}) — backs the Identities tab's
 * "info" drawer.
 *
 * <p>Reads only the local {@code idp_identities}/{@code idp_identity_relations}
 * Postgres cache — never calls out to Keycloak on this request path, the
 * same Zero-Trust-reliability posture every other {@code /api/v1/admin/**}
 * read endpoint already has (policies, audit logs, identity search all read
 * a local cache, not a live upstream call).
 *
 * <p>Security: covered by the same {@code u2s-admin-console-api} YAML rule
 * and {@link AdminAuthorizationFilter} as {@link AdminIdentitySearchController}
 * — that filter's path check is {@code /api/v1/admin/**} generically, so no
 * new security wiring is needed for this new sub-path.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminIdentityRelationsController {

    private final IdpIdentityRelationRepository relationRepository;
    private final IdpIdentityRepository identityRepository;

    AdminIdentityRelationsController(IdpIdentityRelationRepository relationRepository,
                                      IdpIdentityRepository identityRepository) {
        this.relationRepository = relationRepository;
        this.identityRepository = identityRepository;
    }

    @GetMapping("/identities/{id}/relations")
    public Flux<RelatedIdentity> relations(@PathVariable UUID id) {
        return relationRepository.findBySubjectId(id)
                .flatMap(relation -> identityRepository.findById(relation.targetId())
                        .map(target -> toRelatedIdentity(target, relation)));
    }

    private RelatedIdentity toRelatedIdentity(IdpIdentity target, IdpIdentityRelation relation) {
        return new RelatedIdentity(target.id(), target.type(), target.name(), target.displayName(),
                relation.relationType().name());
    }

    /** One row of the "info" drawer's result — the related Group/Role plus how it's related. */
    record RelatedIdentity(UUID id, IdentityType type, String name, String displayName, String relationType) {
    }
}
