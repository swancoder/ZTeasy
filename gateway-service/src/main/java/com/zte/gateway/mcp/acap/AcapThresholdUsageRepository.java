package com.zte.gateway.mcp.acap;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Write-behind store for daily threshold usage (Stage 32, ADR-032).
 * Spring Data needs an entity type parameter, but this repository is used
 * exclusively through its explicit queries — the composite-key row never
 * travels as an entity.
 */
@Repository
public interface AcapThresholdUsageRepository extends ReactiveCrudRepository<AcapThresholdUsageRow, String> {

    @Query("""
            INSERT INTO acap_threshold_usage (agent_id, metric, day, used)
            VALUES (:agentId, :metric, :day, 1)
            ON CONFLICT (agent_id, metric, day)
            DO UPDATE SET used = acap_threshold_usage.used + 1
            RETURNING used
            """)
    Mono<Integer> increment(@Param("agentId") String agentId, @Param("metric") String metric,
                            @Param("day") LocalDate day);

    @Query("SELECT * FROM acap_threshold_usage WHERE day = :day")
    Flux<AcapThresholdUsageRow> findByDay(@Param("day") LocalDate day);
}
