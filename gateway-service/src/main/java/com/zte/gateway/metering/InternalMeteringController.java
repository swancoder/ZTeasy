package com.zte.gateway.metering;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Where token spend is reported from (Stage 29, ADR-029):
 * {@code POST /api/v1/internal/metering/llm}.
 *
 * <p>On the {@code /api/v1/internal/**} prefix deliberately — the reporter is
 * a component inside the perimeter ({@code zt-agents} today, any governed
 * agent later), not an interactive user, so it inherits that prefix's
 * posture: no JWT, and the shared-secret guard
 * ({@code InternalEndpointGuardFilter}) once the gateway is exposed.
 *
 * <p>Returns {@code 202 Accepted}: the write is queued on the async sink, so
 * claiming {@code 201 Created} would overstate what has happened by the time
 * the caller sees the response.
 */
@RestController
@RequestMapping("/api/v1/internal/metering")
class InternalMeteringController {

    private final LlmMeteringService metering;

    InternalMeteringController(LlmMeteringService metering) {
        this.metering = metering;
    }

    @PostMapping("/llm")
    Mono<ResponseEntity<Void>> report(@RequestBody LlmUsageReport report) {
        if (report.agentId() == null || report.agentId().isBlank()
                || report.model() == null || report.model().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        metering.record(LlmUsage.of(
                report.agentId(), report.model(),
                Math.max(0, report.inputTokens()), Math.max(0, report.outputTokens()),
                Math.max(0, report.costMicros()), report.purpose()));
        return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }

    /** Request body — cost is supplied by the reporter, which knows its own model pricing. */
    record LlmUsageReport(String agentId, String model, long inputTokens, long outputTokens,
                          long costMicros, String purpose) {}
}
