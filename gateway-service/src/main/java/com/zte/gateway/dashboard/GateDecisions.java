package com.zte.gateway.dashboard;

/** Gate outcomes over the reporting window (Stage 29, ADR-029). */
public record GateDecisions(long allowed, long held, long denied) {}
