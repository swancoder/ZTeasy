package com.zte.gateway.dashboard;

import com.zte.gateway.governance.AgentActivitySummary;
import com.zte.gateway.inventory.InventoryView;

import java.util.List;

/** CTO view (Stage 29, ADR-029): per-agent gate activity plus registry health. */
public record OperationsPanel(List<AgentActivitySummary> agents, List<InventoryView> registry) {}
