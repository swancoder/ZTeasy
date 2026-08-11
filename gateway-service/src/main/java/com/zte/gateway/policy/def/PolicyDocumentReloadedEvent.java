package com.zte.gateway.policy.def;

/**
 * Published by {@link PolicyDefinitionStore#reload()} only on a successful
 * reload (ADR-014) — lets interested components (e.g.
 * {@code com.zte.gateway.identity.OrphanedRuleChecker}) react to the new
 * document without {@link PolicyDefinitionStore} taking on a dependency on
 * their internals.
 */
public record PolicyDocumentReloadedEvent(PolicyDocument document) {}
