package com.zte.gateway.mcp.acap.lifecycle;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** Reactive access to {@code acap_reauthorizations} (Stage 32, ADR-032). */
@Repository
public interface AcapReauthorizationRepository extends ReactiveCrudRepository<AcapReauthorization, UUID> {

    Flux<AcapReauthorization> findByAgentIdOrderByReauthorizedAtDesc(String agentId);
}
