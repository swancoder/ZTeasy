package com.zte.gateway.metering;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/** Reactive access to {@code llm_usage} (Stage 29, ADR-029). */
@Repository
public interface LlmUsageRepository extends ReactiveCrudRepository<LlmUsage, UUID> {

    /** Everything in the reporting window; aggregation happens in Java, matching GovernanceService's precedent. */
    Flux<LlmUsage> findByTimestampAfterOrderByTimestampDesc(Instant since);
}
