package com.zte.gateway.dashboard;

import com.zte.gateway.metering.LlmMeteringService;

import java.util.List;

/**
 * CFO view (Stage 29, ADR-029).
 *
 * <p>{@code instrumented} is {@code false} when nothing has ever been
 * metered, so the UI can say "not reported yet" instead of showing €0 — a
 * missing integration and a genuinely free month must not look identical.
 */
public record SpendPanel(
        List<LlmMeteringService.DailySpend> daily,
        List<LlmMeteringService.AgentSpend> byAgent,
        LlmMeteringService.SpendTotals totals,
        boolean instrumented
) {}
