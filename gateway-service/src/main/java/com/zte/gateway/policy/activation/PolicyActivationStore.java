package com.zte.gateway.policy.activation;

import com.zte.gateway.policy.def.PolicyRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory snapshot of the activation overlay (Stage 31, ADR-031).
 *
 * <p>Policy evaluation is synchronous and zero-I/O by design (ADR-009/
 * ADR-011), so activation state cannot live behind an R2DBC call on the
 * request path. This component keeps the {@code policy_rule_overrides} table
 * mirrored in a concurrent map: loaded once at startup, updated on every
 * toggle, read with a plain map lookup during evaluation.
 *
 * <p>Overrides deliberately survive policy reloads (the YAML defines rules,
 * the overlay defines whether they act), and an override for a rule id the
 * document no longer contains is inert rather than an error.
 */
@Component
public class PolicyActivationStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyActivationStore.class);

    private final PolicyRuleOverrideRepository repository;
    private final Map<String, Boolean> enabledByRuleId = new ConcurrentHashMap<>();

    public PolicyActivationStore(PolicyRuleOverrideRepository repository) {
        this.repository = repository;
    }

    /**
     * Loaded on application-ready rather than in the constructor: bean
     * construction races Flyway's migration, so a constructor read can fail
     * on the run that creates the table (found live in stage 32 while adding
     * two more stores with the same shape). Until it fires, every rule reads
     * as enabled — the correct default.
     */
    @EventListener(ApplicationReadyEvent.class)
    void load() {
        repository.findAll()
                .doOnNext(o -> enabledByRuleId.put(o.ruleId(), o.enabled()))
                .count()
                .subscribe(
                        n -> log.info("[ZTE-POLICY-ACTIVATION] loaded {} rule override(s)", n),
                        e -> log.warn("[ZTE-POLICY-ACTIVATION] could not load overrides: {}", e.toString()));
    }

    public boolean isEnabled(String ruleId) {
        return enabledByRuleId.getOrDefault(ruleId, true);
    }

    /** The rules that currently act — what every evaluation must run against. */
    public List<PolicyRule> active(List<PolicyRule> rules) {
        return rules.stream().filter(r -> isEnabled(r.id())).toList();
    }

    /** The switched-off rules — matched separately so a would-have-applied hit can be logged. */
    public List<PolicyRule> inactive(List<PolicyRule> rules) {
        return rules.stream().filter(r -> !isEnabled(r.id())).toList();
    }

    /** Persists and applies a toggle; the in-memory map is updated only after the write succeeds. */
    public Mono<PolicyRuleOverride> setEnabled(String ruleId, boolean enabled, String updatedBy) {
        return repository.upsert(ruleId, enabled, updatedBy)
                .then(repository.findById(ruleId))
                .doOnNext(saved -> {
                    enabledByRuleId.put(saved.ruleId(), saved.enabled());
                    log.info("[ZTE-POLICY-ACTIVATION] rule '{}' {} by {}", ruleId,
                            enabled ? "ENABLED" : "DISABLED", updatedBy);
                });
    }

    public Mono<List<PolicyRuleOverride>> all() {
        return repository.findAll().collectList();
    }
}
