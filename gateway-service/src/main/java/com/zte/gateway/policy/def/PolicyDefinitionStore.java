package com.zte.gateway.policy.def;

import com.zte.auth.audit.ZteAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AtomicReference<PolicyDocument> current = new AtomicReference<>();
    private final Object reloadLock = new Object();

    public PolicyDefinitionStore(YamlPolicyFileLoader loader,
                                  PolicyValidator validator,
                                  PolicyDefaultsProperties properties) {
        this.loader = loader;
        this.validator = validator;
        this.properties = properties;
        current.set(loadAndValidateOrThrow());
        log.info("Policy definitions loaded: {} users2service, {} service2service, {} agentMcpToolCalls rules",
                current.get().users2service().size(),
                current.get().service2service().size(),
                current.get().agentMcpToolCalls().size());
    }

    /** Zero-I/O synchronous read — safe to call inline from the reactive gateway path or {@code McpPolicyEngine.evaluate()}. */
    public PolicyDocument current() {
        return current.get();
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
