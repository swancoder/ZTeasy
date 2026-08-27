package com.zte.gateway.dashboard;

import java.util.List;

/** What one agent is scoped to touch — the DPO's core question (Stage 29, ADR-029). */
public record AgentDataScope(String agentId, String territory, boolean writeAllowed, List<ResourceFields> reads) {}
