package com.zte.gateway.policyaudit;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reactive access to {@code policy_audit_runs} (Stage 31, ADR-031). */
@Repository
public interface PolicyAuditRunRepository extends ReactiveCrudRepository<PolicyAuditRun, UUID> {

    Mono<PolicyAuditRun> findTopByOrderByTimestampDesc();
}
