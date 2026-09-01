package com.zte.gateway.policy.activation;

import com.zte.gateway.policy.def.PolicyMatcher;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;

/**
 * Test factory for the activation layer (Stage 31, ADR-031): builds a real
 * {@link ActivePolicyEvaluator} over a store whose repository is mocked —
 * {@code Flux.just} delivers the seeded overrides synchronously inside the
 * store's constructor subscription, so no async coordination is needed.
 */
public final class TestActivation {

    private TestActivation() {}

    /** Everything enabled — the pre-Stage-31 behaviour. */
    public static ActivePolicyEvaluator allActive(PolicyMatcher matcher) {
        return new ActivePolicyEvaluator(matcher, store());
    }

    public static ActivePolicyEvaluator withDisabled(PolicyMatcher matcher, String... disabledRuleIds) {
        return new ActivePolicyEvaluator(matcher, store(disabledRuleIds));
    }

    public static PolicyActivationStore store(String... disabledRuleIds) {
        PolicyRuleOverrideRepository repo = Mockito.mock(PolicyRuleOverrideRepository.class);
        Mockito.when(repo.findAll()).thenReturn(Flux.fromIterable(
                Arrays.stream(disabledRuleIds)
                        .map(id -> new PolicyRuleOverride(id, false, "test", Instant.now()))
                        .toList()));
        Mockito.lenient().when(repo.upsert(Mockito.anyString(), Mockito.anyBoolean(), Mockito.anyString()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, String.class)));
        PolicyActivationStore store = new PolicyActivationStore(repo);
        // Stage 32: loading moved to ApplicationReadyEvent (Flyway race), so
        // tests trigger it explicitly instead of relying on the constructor.
        store.load();
        return store;
    }
}
