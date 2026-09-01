package com.zte.gateway.mcp.acap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-agent-per-metric daily usage counter (Stage 6, ADR-022; persistence
 * Stage 32, ADR-032).
 *
 * <p>The in-memory counter stays authoritative on the request path — policy
 * evaluation is zero-I/O by design (ADR-009) — but every increment is also
 * written behind asynchronously (the SPECS §8 sink shape), and today's rows
 * are restored at startup. A gateway restart therefore no longer resets an
 * agent's daily usage to zero, which previously let a limit be bypassed by
 * bouncing the process.
 *
 * <p>Multi-instance remains approximate: each instance counts in memory and
 * merges through the DB only at startup. Better than the pure-memory status
 * quo, honest about not being a distributed counter.
 */
@Component
public class AcapThresholdTracker {

    private static final Logger log = LoggerFactory.getLogger(AcapThresholdTracker.class);

    private final Map<String, Map<String, AtomicInteger>> counts = new ConcurrentHashMap<>();
    private volatile LocalDate resetDate = LocalDate.now();

    private final AcapThresholdUsageRepository repository;
    private final Sinks.Many<String[]> persistSink;

    public AcapThresholdTracker(AcapThresholdUsageRepository repository) {
        this.repository = repository;
        this.persistSink = Sinks.many().unicast().onBackpressureBuffer();
        this.persistSink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .flatMap(key -> repository.increment(key[0], key[1], LocalDate.now())
                        .doOnError(e -> log.warn("[ZTE-ACAP-THRESHOLD] persist failed {}/{}: {}",
                                key[0], key[1], e.toString()))
                        .onErrorResume(e -> Mono.empty()))
                .subscribe();
    }

    /**
     * Restored on application-ready, NOT in the constructor: bean
     * construction races Flyway (observed live — the restore read failed on
     * the run that created the table). Until this fires, counters start at
     * zero, which is the pre-persistence behaviour.
     */
    @EventListener(ApplicationReadyEvent.class)
    void restoreToday() {
        repository.findByDay(LocalDate.now())
                .doOnNext(row -> counts
                        .computeIfAbsent(row.agentId(), id -> new ConcurrentHashMap<>())
                        .computeIfAbsent(row.metric(), m -> new AtomicInteger(0))
                        .set(row.used()))
                .count()
                .subscribe(
                        n -> log.info("[ZTE-ACAP-THRESHOLD] restored {} usage counter(s) for today", n),
                        e -> log.warn("[ZTE-ACAP-THRESHOLD] could not restore usage: {}", e.toString()));
    }

    /** Increments and returns the new count, resetting everything first if the day has rolled over. */
    public synchronized int incrementAndGet(String agentId, String metric) {
        rolloverIfNewDay();
        int value = counts.computeIfAbsent(agentId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(metric, m -> new AtomicInteger(0))
                .incrementAndGet();
        persistSink.tryEmitNext(new String[]{agentId, metric});
        return value;
    }

    /** Read-only — the Admin Console's current usage display; never increments. */
    public synchronized int currentCount(String agentId, String metric) {
        rolloverIfNewDay();
        Map<String, AtomicInteger> agentCounts = counts.get(agentId);
        if (agentCounts == null) {
            return 0;
        }
        AtomicInteger counter = agentCounts.get(metric);
        return counter == null ? 0 : counter.get();
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(resetDate)) {
            counts.clear();
            resetDate = today;
        }
    }
}
