package com.zte.gateway.identity;

import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyDocumentReloadedEvent;
import com.zte.gateway.policy.def.PolicyRule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Purely observational check (ADR-014; extended to {@code service2service}/
 * {@code agentMcpToolCalls} by ADR-015, {@code agentMcpToolHolds} by
 * ADR-019): for every rule in all four categories whose {@code source}
 * parses to a concrete {@link IdentityUrn}
 * (wildcard sources are skipped — not checkable against a fixed identity
 * list), warns via SLF4J when no matching identity exists in the local
 * {@code idp_identities} cache. {@code users2service} sources default a
 * bare (no-prefix) name to {@link IdentityType#ROLE}; {@code
 * service2service}/{@code agentMcpToolCalls} default to {@link
 * IdentityType#CLIENT} — every pre-ADR-015 rule in those two categories was
 * already a bare OAuth2 client id. Never rejects, blocks, or mutates the
 * policy document — that is a deliberate product decision (task explicitly
 * says "DO NOT reject"), not an oversight.
 *
 * <p>Runs at startup ({@link #checkOnStartup()}) and on every successful
 * {@link PolicyDocumentReloadedEvent}. Decoupled from {@link
 * IdentitySyncService}'s own independent {@code @Scheduled} startup run —
 * on a cold start, this check can legitimately run before the first sync
 * populates the cache, producing a transient false-positive warning that
 * self-corrects once sync completes (within the sync interval, or
 * immediately after a manual sync or policy reload). Accepted for MVP
 * rather than sequencing two independent async initializers.
 */
@Component
public class OrphanedRuleChecker {

    private static final Logger log = LoggerFactory.getLogger("ZTE-ORPHANED-RULE-CHECK");

    private final IdpIdentityRepository repository;
    private final PolicyDefinitionStore policyDefinitionStore;

    public OrphanedRuleChecker(IdpIdentityRepository repository, PolicyDefinitionStore policyDefinitionStore) {
        this.repository = repository;
        this.policyDefinitionStore = policyDefinitionStore;
    }

    @PostConstruct
    void checkOnStartup() {
        check(policyDefinitionStore.current());
    }

    @EventListener(PolicyDocumentReloadedEvent.class)
    void onReload(PolicyDocumentReloadedEvent event) {
        check(event.document());
    }

    private void check(PolicyDocument document) {
        Flux.merge(
                        Flux.fromIterable(document.users2service())
                                .flatMap(rule -> checkRule(rule, IdentityType.ROLE)),
                        Flux.fromIterable(document.service2service())
                                .flatMap(rule -> checkRule(rule, IdentityType.CLIENT)),
                        Flux.fromIterable(document.agentMcpToolCalls())
                                .flatMap(rule -> checkRule(rule, IdentityType.CLIENT)),
                        Flux.fromIterable(document.agentMcpToolHolds())
                                .flatMap(rule -> checkRule(rule, IdentityType.CLIENT)))
                .subscribe(v -> {}, ex -> log.error("[ZTE-ORPHANED-RULE-CHECK] check stream failed", ex));
    }

    private Mono<Void> checkRule(PolicyRule rule, IdentityType defaultType) {
        return IdentityUrn.parse(rule.source(), defaultType)
                .map(urn -> repository.existsByTypeAndName(urn.type().name(), urn.name())
                        .doOnNext(exists -> {
                            if (!exists) {
                                log.warn("[ZTE-ORPHANED-RULE-CHECK] ORPHANED RULE: rule id={} source={} — "
                                                + "no matching {} '{}' found in the synced identity cache",
                                        rule.id(), rule.source(), urn.type(), urn.name());
                            }
                        })
                        .then()
                        // Per-rule, not per-check: a single query failure (e.g. the
                        // startup check racing Flyway's V5 migration — R2DBC has no
                        // ordering relationship with the JDBC datasource Flyway uses)
                        // must not kill the whole Flux via flatMap's single-error
                        // propagation, which silently drops every other in-flight
                        // rule's failure as "onErrorDropped" — found live, not by the
                        // mocked unit tests. Purely observational by design anyway;
                        // degrading to a log line matches that contract exactly.
                        .onErrorResume(ex -> {
                            log.warn("[ZTE-ORPHANED-RULE-CHECK] check failed for rule id={} source={}: {}",
                                    rule.id(), rule.source(), ex.toString());
                            return Mono.empty();
                        }))
                .orElse(Mono.empty());
    }
}
