package com.zte.gateway.mcp.acap.lifecycle;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** Reactive access to {@code acap_profile_lifecycle} (Stage 32, ADR-032). */
@Repository
public interface AcapLifecycleRepository extends ReactiveCrudRepository<AcapLifecycleState, String> {

    /** Same not-{@code @Modifying} RETURNING convention as the other upserts (SPECS §8). */
    @Query("""
            INSERT INTO acap_profile_lifecycle (agent_id, status, reauth_due, updated_by, updated_at)
            VALUES (:agentId, :status, :reauthDue, :updatedBy, NOW())
            ON CONFLICT (agent_id)
            DO UPDATE SET status = :status,
                          reauth_due = COALESCE(:reauthDue, acap_profile_lifecycle.reauth_due),
                          updated_by = :updatedBy, updated_at = NOW()
            RETURNING agent_id
            """)
    Mono<String> upsert(@Param("agentId") String agentId, @Param("status") String status,
                        @Param("reauthDue") LocalDate reauthDue, @Param("updatedBy") String updatedBy);
}
