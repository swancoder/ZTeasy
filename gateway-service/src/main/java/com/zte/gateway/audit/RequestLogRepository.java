package com.zte.gateway.audit;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link RequestLog}.
 */
@Repository
public interface RequestLogRepository extends ReactiveCrudRepository<RequestLog, UUID> {

    /** Latest 100 rows, newest first — the {@code LIMIT} is applied at the query level. */
    Flux<RequestLog> findTop100ByOrderByTimestampDesc();
}
