package com.zte.gateway.dashboard;

import java.util.List;

/** DPO view (Stage 29, ADR-029). */
public record DataProtectionPanel(List<AgentDataScope> scopes) {}
