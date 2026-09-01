package com.zte.gateway.mcp.acap;

import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * In-memory-only tracker for tests (Stage 32, ADR-032): the persistence
 * repository is mocked, so behaviour is exactly the pre-persistence counter
 * plus an optional seeded "already used today" state.
 */
public final class TestThresholdTracker {

    private TestThresholdTracker() {}

    public static AcapThresholdTracker empty() {
        return seeded();
    }

    public static AcapThresholdTracker seeded(AcapThresholdUsageRow... rows) {
        AcapThresholdUsageRepository repo = Mockito.mock(AcapThresholdUsageRepository.class);
        Mockito.when(repo.findByDay(Mockito.any(LocalDate.class)))
                .thenReturn(Flux.fromIterable(Arrays.asList(rows)));
        Mockito.lenient().when(repo.increment(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(1));
        AcapThresholdTracker tracker = new AcapThresholdTracker(repo);
        tracker.restoreToday();   // ApplicationReadyEvent-driven in production
        return tracker;
    }
}
