package com.zte.agents.auditor

import com.zte.agents.client.AnthropicClient
import com.zte.agents.client.GatewayClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

private const val SYSTEM_PROMPT = """You are a Zero Trust Security Auditor specializing in \
access control policy analysis.

Your task is to analyze the provided ZTE (Zero Trust Environment) access policies and produce \
a structured security report. Focus on:
- Overly broad permissions (wildcards, permissive path patterns)
- Missing least-privilege principles (roles with more access than needed)
- Unused or redundant roles
- Paths that should be more restricted
- Any policy gaps (resources with no policy coverage)

Return your findings as a structured Markdown report with the following sections:
## Executive Summary
## Policy Inventory
## Risk Findings (with severity: HIGH / MEDIUM / LOW)
## Recommendations
## Compliance Notes (Zero Trust principles adherence)"""

/**
 * Orchestrates the policy audit pipeline:
 * 1. Fetch all access policies from the gateway's internal endpoint.
 * 2. Format them into a structured user message.
 * 3. Call the Anthropic LLM with the security auditor system prompt.
 * 4. Return the Markdown audit report.
 */
@Service
class PolicyAuditorService(
    private val gatewayClient: GatewayClient,
    private val anthropicClient: AnthropicClient
) {
    private val log = LoggerFactory.getLogger(PolicyAuditorService::class.java)

    fun runAudit(): Mono<String> {
        log.info("Policy audit started")
        return gatewayClient.fetchPolicies()
            .collectList()
            .flatMap { policies ->
                if (policies.isEmpty()) {
                    log.warn("No policies returned from gateway — audit aborted")
                    return@flatMap Mono.just("**Audit aborted:** No access policies found in the gateway.")
                }
                log.info("Auditing {} policies", policies.size)
                val userMessage = buildString {
                    appendLine("Please audit the following ZTE access policies:")
                    appendLine()
                    appendLine("Total policies: ${policies.size}")
                    appendLine()
                    policies.forEach { appendLine(it.toAuditLine()) }
                }
                anthropicClient.complete(SYSTEM_PROMPT, userMessage)
            }
            .doOnSuccess { log.info("Policy audit complete") }
    }
}
