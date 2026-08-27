package com.zte.gateway.dashboard;

import java.util.List;

/** One resource an agent may read, and exactly which fields (Stage 29, ADR-029). */
public record ResourceFields(String resource, List<String> fields) {}
