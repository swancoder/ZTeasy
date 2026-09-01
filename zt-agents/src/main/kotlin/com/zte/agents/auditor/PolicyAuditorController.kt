package com.zte.agents.auditor

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

data class AuditReport(val report: String)

/**
 * REST entry point for the Policy Auditor Agent.
 *
 * POST /api/v1/agents/auditor/run
 *   → fetches gateway policies → calls Anthropic → returns Markdown audit report as JSON.
 *
 * The endpoint is reactive: no thread is blocked while the LLM responds.
 * Expect 10-120 second response times depending on policy count and Anthropic latency.
 */
@RestController
@RequestMapping("/api/v1/agents/auditor", produces = [MediaType.APPLICATION_JSON_VALUE])
class PolicyAuditorController(
    private val auditorService: PolicyAuditorService,
    private val structuredAuditService: StructuredAuditService
) {

    @PostMapping("/run")
    fun runAudit(): Mono<AuditReport> =
        auditorService.runAudit().map { AuditReport(it) }

    /**
     * Structured audit (Stage 31, ADR-031): the caller SUPPLIES the policy
     * document instead of this service fetching it — works regardless of
     * whether this service trusts the gateway's TLS (the ADR-027 cloud gap).
     */
    @PostMapping("/analyze")
    fun analyze(@RequestBody request: AnalyzeRequest): Mono<AnalyzeResponse> =
        structuredAuditService.analyze(request)
}
