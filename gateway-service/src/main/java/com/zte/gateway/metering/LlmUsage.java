package com.zte.gateway.metering;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One LLM call's token usage and cost (Stage 29, ADR-029) — the data behind
 * the executive dashboard's spend tiles.
 *
 * <p>Written out-of-band by whoever spent the tokens ({@code zt-agents} for
 * its Policy Auditor runs; any governed agent may report its own via
 * {@code POST /api/v1/internal/metering/llm}), never on the gateway's own
 * request path.
 *
 * <p>{@code costMicros} is integer micro-euros, and it is <em>stored</em>
 * rather than derived at read time: model prices change, and a report for a
 * past window must keep the price that applied then.
 *
 * <p>{@code id} left {@code null} on construction — the DB generates it
 * (SPECS §8's R2DBC convention).
 */
@Table("llm_usage")
public record LlmUsage(
        @Id UUID id,
        Instant timestamp,
        @Column("agent_id") String agentId,
        String model,
        @Column("input_tokens") long inputTokens,
        @Column("output_tokens") long outputTokens,
        @Column("cost_micros") long costMicros,
        String purpose
) {
    public static LlmUsage of(String agentId, String model, long inputTokens, long outputTokens,
                              long costMicros, String purpose) {
        return new LlmUsage(null, Instant.now(), agentId, model, inputTokens, outputTokens, costMicros, purpose);
    }
}
