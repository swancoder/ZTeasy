package com.zte.gateway.mcp.acap.lifecycle;

import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Test factory for the ACAP lifecycle overlay (Stage 32, ADR-032), mirroring
 * {@code TestActivation}: a real store over a mocked repository whose
 * {@code findAll} delivers synchronously inside the constructor.
 */
public final class TestLifecycle {

    private TestLifecycle() {}

    /** No rows — every agent ACTIVE with its file's own re-authorization date. */
    public static AcapLifecycleStore empty() {
        return store();
    }

    public static AcapLifecycleStore withStatus(String agentId, String status) {
        return store(new AcapLifecycleState(agentId, status, null, "test", Instant.now()));
    }

    public static AcapLifecycleStore withReauthDue(String agentId, LocalDate due) {
        return store(new AcapLifecycleState(agentId, AcapLifecycleState.ACTIVE, due, "test", Instant.now()));
    }

    public static AcapLifecycleStore store(AcapLifecycleState... states) {
        AcapLifecycleRepository repo = Mockito.mock(AcapLifecycleRepository.class);
        Mockito.when(repo.findAll()).thenReturn(Flux.fromIterable(Arrays.asList(states)));
        AcapReauthorizationRepository reauthRepo = Mockito.mock(AcapReauthorizationRepository.class);
        AcapLifecycleStore store = new AcapLifecycleStore(repo, reauthRepo);
        store.load();   // loading is ApplicationReadyEvent-driven in production
        return store;
    }
}
