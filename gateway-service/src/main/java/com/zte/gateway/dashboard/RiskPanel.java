package com.zte.gateway.dashboard;

import com.zte.gateway.audit.RequestLog;

import java.util.List;

/** Board / Risk view (Stage 29, ADR-029): risk tiers plus recent refusals. */
public record RiskPanel(List<AcapProfileRisk> profiles, List<RequestLog> outOfPolicy) {}
