package com.zte.gateway.policy.def;

import com.zte.auth.audit.ZteAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently active, validated {@link PolicyDocument} behind an
 * {@link AtomicReference}, mirroring the hot-swap pattern
 * {@code auth-library}'s {@code ReloadableSslContextFactory} already uses for
 * {@code SslContext} (ADR-004).
 *
 * <p>Startup: loads and validates the configured YAML file synchronously in the
 * constructor. Any failure throws {@link PolicyLoadException}, which fails
 * Spring's {@code ApplicationContext} refresh — the gateway never starts serving
 * traffic with an empty or stale rule set (fail-fast, consistent with
 * {@code SecurityConfig}'s deny-by-default posture).
 *
 * <p>Runtime reload: {@link #reload()} re-reads and re-validates the file off the
 * Netty event loop, then atomically swaps the reference — in-flight requests that
 * already captured a reference via {@link #current()} keep using the pre-reload
 * document to completion. Reload triggers are serialized by a {@code synchronized}
 * compute-then-swap so two concurrent triggers cannot interleave (the store's
 * request-serving path, {@link #current()}, never blocks and never locks). A
 * reload that fails validation leaves the previously loaded document in effect.
 */
@Component
public class PolicyDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyDefinitionStore.class);

    private final YamlPolicyFileLoader loader;
    private final PolicyValidator validator;
    private final PolicyDefaultsProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<PolicyDocument> current = new AtomicReference<>();
    private final Object reloadLock = new Object();

    public PolicyDefinitionStore(YamlPolicyFileLoader loader,
                                  PolicyValidator validator,
                                  PolicyDefaultsProperties properties,
                                  ApplicationEventPublisher eventPublisher) {
        this.loader = loader;
        this.validator = validator;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        current.set(loadAndValidateOrThrow());
        log.info("Policy definitions loaded: {} users2service, {} service2service, {} agentMcpToolCalls, "
                        + "{} agentMcpToolHolds rules",
                current.get().users2service().size(),
                current.get().service2service().size(),
                current.get().agentMcpToolCalls().size(),
                current.get().agentMcpToolHolds().size());
    }

    /** Zero-I/O synchronous read — safe to call inline from the reactive gateway path or {@code McpPolicyEngine.evaluate()}. */
    public PolicyDocument current() {
        return current.get();
    }

    /**
     * Re-reads the policy file on a timer so that every instance converges on what is
     * actually on disk (ADR-039).
     *
     * <p>{@link #reload()} only reloads the instance that served the request, and this
     * deployment runs two gateways behind two front doors (ADR-028): pressing "Reload
     * Policies" in the console reloaded the browser-facing one while the agent-facing
     * one — the instance that decides MCP calls — kept the old document. Observed live:
     * a freshly granted rule had no effect on a chat tool call until the second
     * instance was reloaded by hand.
     *
     * <p>Same shape as the ADR-032 overlay refresh, and the same honest caveat: this is
     * polling, not invalidation. An edit takes effect on every instance within one
     * interval, and the explicit endpoint stays for when that wait is too long.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${zte.policy.file-refresh-ms:30000}",
            initialDelayString = "${zte.policy.file-refresh-ms:30000}")
    void refreshFromFile() {
        synchronized (reloadLock) {
            try {
                PolicyDocument next = loadAndValidateOrThrow();
                if (!next.equals(current.get())) {
                    current.set(next);
                    log.info("[ZTE-POLICY] picked up an edited policy file: {} rule(s)",
                            next.allRules().size());
                }
            } catch (RuntimeException e) {
                // A half-written or invalid file must never replace a working policy set —
                // same rule the explicit reload follows.
                log.warn("[ZTE-POLICY] scheduled re-read failed, keeping the loaded policy: {}", e.toString());
            }
        }
    }

    /** Re-reads and re-validates the configured file, off the Netty event loop, atomically swapping on success. */
    public Mono<PolicyReloadResult> reload() {
        return Mono.fromCallable(this::doReload)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private PolicyReloadResult doReload() {
        synchronized (reloadLock) {
            ZteAuditLogger.policyReloadTriggered();
            try {
                PolicyDocument next = loadAndValidateOrThrow();
                current.set(next);
                ZteAuditLogger.policyReloadSucceeded();
                eventPublisher.publishEvent(new PolicyDocumentReloadedEvent(next));
                return PolicyReloadResult.ok();
            } catch (PolicyLoadException e) {
                List<String> errors = e.errors().isEmpty() ? List.of(e.getMessage()) : e.errors();
                log.warn("Policy reload failed, keeping previously loaded policy set: {}", errors);
                ZteAuditLogger.policyReloadFailed(String.join("; ", errors));
                return PolicyReloadResult.failure(errors);
            }
        }
    }

    private PolicyDocument loadAndValidateOrThrow() {
        PolicyDocument document = loader.load(properties.getFile());
        PolicyValidationResult result = validator.validate(document);
        if (!result.warnings().isEmpty()) {
            log.warn("Policy document loaded with warnings: {}", result.warnings());
        }
        if (!result.isValid()) {
            throw new PolicyLoadException(
                    "Policy document failed validation with " + result.errors().size() + " error(s)",
                    result.errors());
        }
        return document;
    }
}
