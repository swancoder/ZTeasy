package com.zte.gateway.audit;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link RequestLog}.
 */
@Repository
public interface RequestLogRepository extends ReactiveCrudRepository<RequestLog, UUID> {

    /** Latest 100 rows, newest first — the {@code LIMIT} is applied at the query level. */
    Flux<RequestLog> findTop100ByOrderByTimestampDesc();

    /**
     * MCP rows only ({@code agent_id IS NOT NULL} — REST traffic never sets
     * it) since {@code since}, newest first — the governance dashboard's
     * per-agent activity summary (ADR-021) aggregates these in memory rather
     * than via a SQL {@code GROUP BY}, matching this codebase's established
     * "join/aggregate in Java at this demo's scale" convention (e.g.
     * {@code InventoryService#list}).
     */
    Flux<RequestLog> findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc(Instant since);

    /**
     * Latest 50 MCP-agent {@code decisionEffect} rows, newest first — the
     * governance dashboard's "out-of-policy attempts" feed (ADR-021) is
     * {@code decisionEffect = "DENY"}, deliberately MCP-only ({@code
     * agent_id IS NOT NULL}): this is about agent behavior specifically,
     * distinct from the Audit Trail tab's REST+MCP-wide view.
     */
    Flux<RequestLog> findTop50ByAgentIdIsNotNullAndDecisionEffectOrderByTimestampDesc(String decisionEffect);

    /**
     * One person's own trail (ADR-039): everything the gateway decided where they
     * were the subject — as the MCP caller ({@code agent_id}, which for a human is
     * their username) or as the human behind a service call
     * ({@code original_user_obo}).
     *
     * <p>Deliberately scoped in the query rather than filtered after reading: a
     * "my events" endpoint that fetches everyone's rows and then hides most of
     * them is one bug away from showing them.
     */
    @Query("""
            SELECT * FROM request_logs
             WHERE agent_id = :who OR original_user_obo = :who
             ORDER BY timestamp DESC
             LIMIT :limit
            """)
    Flux<RequestLog> findOwnEvents(@Param("who") String who, @Param("limit") int limit);
}
