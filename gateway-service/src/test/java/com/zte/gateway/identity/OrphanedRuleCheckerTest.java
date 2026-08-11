package com.zte.gateway.identity;

import com.zte.gateway.policy.def.PolicyDefinitionStore;
import com.zte.gateway.policy.def.PolicyDocument;
import com.zte.gateway.policy.def.PolicyDocumentReloadedEvent;
import com.zte.gateway.policy.def.PolicyRule;
import com.zte.gateway.policy.def.RuleEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrphanedRuleChecker}.
 *
 * <p>{@code repository.existsByTypeAndName} is stubbed with {@link Mono#just},
 * which resolves synchronously on subscribe — no scheduler hop happens in
 * {@link OrphanedRuleChecker#check}, so assertions can run immediately after
 * {@code checkOnStartup()}/{@code onReload()} return without polling.
 */
@ExtendWith(MockitoExtension.class)
class OrphanedRuleCheckerTest {

    @Mock IdpIdentityRepository repository;
    @Mock PolicyDefinitionStore policyDefinitionStore;

    @BeforeEach
    void setUp() {
        lenient().when(repository.existsByTypeAndName(any(), any())).thenReturn(Mono.just(true));
    }

    @Test
    void startup_checksEachParseableRule() {
        PolicyDocument document = new PolicyDocument(1, List.of(
                new PolicyRule("r1", RuleEffect.ALLOW, "role:ADMIN", "service-a", null, null, 0),
                new PolicyRule("r2", RuleEffect.ALLOW, "user:zte-admin", "service-a", null, null, 0),
                new PolicyRule("r3", RuleEffect.ALLOW, "group:ops", "service-a", null, null, 0)
        ), List.of(), List.of());
        when(policyDefinitionStore.current()).thenReturn(document);

        new OrphanedRuleChecker(repository, policyDefinitionStore).checkOnStartup();

        verify(repository).existsByTypeAndName(eq("ROLE"), eq("ADMIN"));
        verify(repository).existsByTypeAndName(eq("USER"), eq("zte-admin"));
        verify(repository).existsByTypeAndName(eq("GROUP"), eq("ops"));
    }

    @Test
    void wildcardSource_neverQueried() {
        PolicyDocument document = new PolicyDocument(1, List.of(
                new PolicyRule("r1", RuleEffect.ALLOW, "*", "service-a", null, null, 0)
        ), List.of(), List.of());
        when(policyDefinitionStore.current()).thenReturn(document);

        new OrphanedRuleChecker(repository, policyDefinitionStore).checkOnStartup();

        verify(repository, never()).existsByTypeAndName(any(), any());
    }

    @Test
    void oneRuleQueryFailure_doesNotStopCheckingOtherRules() {
        PolicyDocument document = new PolicyDocument(1, List.of(
                new PolicyRule("r1", RuleEffect.ALLOW, "role:ADMIN", "service-a", null, null, 0),
                new PolicyRule("r2", RuleEffect.ALLOW, "role:USER", "service-a", null, null, 0)
        ), List.of(), List.of());
        when(policyDefinitionStore.current()).thenReturn(document);
        when(repository.existsByTypeAndName(eq("ROLE"), eq("ADMIN")))
                .thenReturn(Mono.error(new RuntimeException("relation \"idp_identities\" does not exist")));
        when(repository.existsByTypeAndName(eq("ROLE"), eq("USER"))).thenReturn(Mono.just(true));

        new OrphanedRuleChecker(repository, policyDefinitionStore).checkOnStartup();

        verify(repository).existsByTypeAndName(eq("ROLE"), eq("ADMIN"));
        verify(repository).existsByTypeAndName(eq("ROLE"), eq("USER"));
    }

    @Test
    void reloadEvent_reChecksNewDocument() {
        when(policyDefinitionStore.current()).thenReturn(PolicyDocument.empty());
        OrphanedRuleChecker checker = new OrphanedRuleChecker(repository, policyDefinitionStore);
        checker.checkOnStartup();

        PolicyDocument reloaded = new PolicyDocument(1, List.of(
                new PolicyRule("r1", RuleEffect.ALLOW, "role:NOBODY", "service-a", null, null, 0)
        ), List.of(), List.of());
        checker.onReload(new PolicyDocumentReloadedEvent(reloaded));

        verify(repository).existsByTypeAndName(eq("ROLE"), eq("NOBODY"));
    }
}
