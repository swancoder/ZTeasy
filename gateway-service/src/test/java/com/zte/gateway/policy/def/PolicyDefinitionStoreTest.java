package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PolicyDefinitionStore}.
 */
@ExtendWith(MockitoExtension.class)
class PolicyDefinitionStoreTest {

    @Mock YamlPolicyFileLoader loader;
    @Mock PolicyValidator validator;
    @Mock PolicyDefaultsProperties properties;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void constructor_validDocument_loadsSuccessfully() {
        PolicyDocument doc = PolicyDocument.empty();
        when(loader.load(any())).thenReturn(doc);
        when(validator.validate(doc)).thenReturn(PolicyValidationResult.valid());

        PolicyDefinitionStore store = new PolicyDefinitionStore(loader, validator, properties, eventPublisher);

        assertThat(store.current()).isEqualTo(doc);
    }

    @Test
    void constructor_invalidDocument_failsFast() {
        PolicyDocument doc = PolicyDocument.empty();
        when(loader.load(any())).thenReturn(doc);
        when(validator.validate(doc)).thenReturn(new PolicyValidationResult(List.of("bad rule"), List.of()));

        assertThatThrownBy(() -> new PolicyDefinitionStore(loader, validator, properties, eventPublisher))
                .isInstanceOf(PolicyLoadException.class);
    }

    @Test
    void reload_success_atomicallySwapsDocument() {
        PolicyDocument initial = PolicyDocument.empty();
        PolicyDocument updated = new PolicyDocument(1,
                List.of(new PolicyRule("u1", RuleEffect.ALLOW, "ADMIN", "service-a", null, null, 0)),
                List.of(), List.of());

        when(loader.load(any())).thenReturn(initial, updated);
        when(validator.validate(any())).thenReturn(PolicyValidationResult.valid());

        PolicyDefinitionStore store = new PolicyDefinitionStore(loader, validator, properties, eventPublisher);
        assertThat(store.current()).isEqualTo(initial);

        StepVerifier.create(store.reload())
                .assertNext(result -> assertThat(result.success()).isTrue())
                .verifyComplete();

        assertThat(store.current()).isEqualTo(updated);
        verify(eventPublisher).publishEvent(new PolicyDocumentReloadedEvent(updated));
    }

    @Test
    void reload_failure_keepsPreviousDocumentActive() {
        PolicyDocument initial = PolicyDocument.empty();

        when(loader.load(any())).thenReturn(initial);
        when(validator.validate(initial))
                .thenReturn(PolicyValidationResult.valid())   // constructor load
                .thenReturn(new PolicyValidationResult(List.of("invalid on reload"), List.of())); // reload

        PolicyDefinitionStore store = new PolicyDefinitionStore(loader, validator, properties, eventPublisher);

        StepVerifier.create(store.reload())
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.errors()).contains("invalid on reload");
                })
                .verifyComplete();

        assertThat(store.current()).isEqualTo(initial);
        verifyNoInteractions(eventPublisher);
    }
}
