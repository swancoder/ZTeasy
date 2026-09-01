package com.zte.gateway.mcp.approval;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/** Reactive R2DBC repository for {@link PendingApproval} (Stage 1, ADR-019). */
public interface PendingApprovalRepository extends ReactiveCrudRepository<PendingApproval, UUID> {

    Flux<PendingApproval> findByStatusOrderByRequestedAtAsc(String status);

    /** Pending rows whose deadline has passed — swept to EXPIRED (ADR-034). */
    Flux<PendingApproval> findByStatusAndExpiresAtBefore(String status, Instant cutoff);
}
