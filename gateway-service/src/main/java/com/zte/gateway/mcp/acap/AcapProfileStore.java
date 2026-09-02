package com.zte.gateway.mcp.acap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently loaded {@link AcapProfile}s behind an {@link
 * AtomicReference}, keyed by agent id (Stage 3, ADR-020) — same hot-swap
 * shape as {@code PolicyDefinitionStore}, deliberately not shared code with
 * it: that store's constructor is fail-fast (a bad policy file must not let
 * the gateway start), this one is best-effort (see {@link
 * AcapProfileFileLoader}'s Javadoc for why that's the right posture here).
 */
@Component
public class AcapProfileStore {

    private static final Logger log = LoggerFactory.getLogger(AcapProfileStore.class);

    private final AcapProfileFileLoader loader;
    private final AcapProfileProperties properties;
    private final AtomicReference<Map<String, AcapProfile>> current = new AtomicReference<>();

    public AcapProfileStore(AcapProfileFileLoader loader, AcapProfileProperties properties) {
        this.loader = loader;
        this.properties = properties;
        current.set(loader.loadAll(properties.getProfilesLocation()));
        log.info("ACAP profiles loaded: {} agent(s) [{}]", current.get().size(), current.get().keySet());
    }

    /** Zero-I/O synchronous read — safe to call inline from {@code YamlMcpPolicyEngine.evaluate()}. */
    public Optional<AcapProfile> find(String agentId) {
        return Optional.ofNullable(current.get().get(agentId));
    }

    /**
     * First profile matching any of {@code keys}, in order (ADR-039). A person is
     * looked up by username first and then by each of their roles, so scope can be
     * authored once for "sales in EMEA" rather than per employee — while a
     * personal profile still wins where one exists.
     */
    public Optional<AcapProfile> find(java.util.List<String> keys) {
        return findKey(keys).map(k -> current.get().get(k));
    }

    /** Which of {@code keys} a profile was found under — the lifecycle is keyed by it too. */
    public Optional<String> findKey(java.util.List<String> keys) {
        if (keys == null) {
            return Optional.empty();
        }
        var profiles = current.get();
        return keys.stream().filter(profiles::containsKey).findFirst();
    }

    public List<AcapProfile> all() {
        return List.copyOf(current.get().values());
    }

    /** Re-reads the configured location off the Netty event loop, atomically swapping on completion. */
    public Mono<AcapProfileReloadResult> reload() {
        return Mono.fromCallable(this::doReload).subscribeOn(Schedulers.boundedElastic());
    }

    private AcapProfileReloadResult doReload() {
        Map<String, AcapProfile> next = loader.loadAll(properties.getProfilesLocation());
        current.set(next);
        log.info("ACAP profiles reloaded: {} agent(s) [{}]", next.size(), next.keySet());
        return AcapProfileReloadResult.of(next.size());
    }
}
