package com.zte.gateway.metering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Records and aggregates LLM token spend (Stage 29, ADR-029).
 *
 * <p>Writes use the same fire-and-forget shape as every other async writer in
 * this codebase (SPECS §8) — a metering write must never delay or fail the
 * caller that reported it.
 *
 * <p>Reads aggregate in Java rather than SQL, matching {@code
 * GovernanceService}'s precedent: the row counts here are dashboard-sized,
 * and keeping the arithmetic in one place makes the "what does this tile
 * actually count" question answerable by reading one method.
 */
@Service
public class LlmMeteringService {

    private static final Logger log = LoggerFactory.getLogger("ZTE-METERING");

    private final LlmUsageRepository repository;
    private final reactor.core.publisher.Sinks.Many<LlmUsage> sink;

    public LlmMeteringService(LlmUsageRepository repository) {
        this.repository = repository;
        this.sink = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        this.sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .flatMap(usage -> repository.save(usage)
                        .doOnError(e -> log.warn("[ZTE-METERING] write failed agent={} model={}: {}",
                                usage.agentId(), usage.model(), e.toString()))
                        .onErrorResume(e -> Mono.empty()))
                .subscribe();
    }

    /** Non-blocking record — returns immediately, mirroring RequestLogAuditService. */
    public void record(LlmUsage usage) {
        log.info("[ZTE-METERING] agent={} model={} in={} out={} cost={}µ€ purpose={}",
                usage.agentId(), usage.model(), usage.inputTokens(), usage.outputTokens(),
                usage.costMicros(), usage.purpose());
        sink.tryEmitNext(usage);
    }

    /** Per-agent totals over the window, biggest spender first. */
    public Mono<List<AgentSpend>> spendByAgent(int hours) {
        return rows(hours).collectList().map(rows -> rows.stream()
                .collect(Collectors.groupingBy(LlmUsage::agentId))
                .entrySet().stream()
                .map(e -> new AgentSpend(
                        e.getKey(),
                        e.getValue().stream().mapToLong(LlmUsage::inputTokens).sum(),
                        e.getValue().stream().mapToLong(LlmUsage::outputTokens).sum(),
                        e.getValue().stream().mapToLong(LlmUsage::costMicros).sum(),
                        e.getValue().size()))
                .sorted(Comparator.comparingLong(AgentSpend::costMicros).reversed())
                .toList());
    }

    /**
     * One point per calendar day (UTC) across the window, oldest first, with
     * empty days filled in as zero — a spend chart with holes reads as "no
     * data" when it means "nothing was spent".
     */
    public Mono<List<DailySpend>> dailySpend(int days) {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        return rows(days * 24).collectList().map(rows -> {
            Map<LocalDate, Long> byDay = rows.stream().collect(Collectors.groupingBy(
                    u -> u.timestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                    TreeMap::new,
                    Collectors.summingLong(LlmUsage::costMicros)));
            return java.util.stream.Stream.iterate(from, d -> d.plusDays(1))
                    .limit(days)
                    .map(d -> new DailySpend(d.toString(), byDay.getOrDefault(d, 0L)))
                    .toList();
        });
    }

    /** Total cost and call count over the window. */
    public Mono<SpendTotals> totals(int hours) {
        return rows(hours).collectList().map(rows -> new SpendTotals(
                rows.stream().mapToLong(LlmUsage::costMicros).sum(),
                rows.stream().mapToLong(LlmUsage::inputTokens).sum(),
                rows.stream().mapToLong(LlmUsage::outputTokens).sum(),
                rows.size()));
    }

    private Flux<LlmUsage> rows(int hours) {
        return repository.findByTimestampAfterOrderByTimestampDesc(Instant.now().minus(Duration.ofHours(hours)));
    }

    public record AgentSpend(String agentId, long inputTokens, long outputTokens, long costMicros, int calls) {}

    public record DailySpend(String date, long costMicros) {}

    public record SpendTotals(long costMicros, long inputTokens, long outputTokens, int calls) {}
}
