package com.zte.agents.metering

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import com.zte.agents.client.GatewayWebClients
import org.springframework.stereotype.Component

/**
 * Reports this agent's LLM token spend to the gateway (ZTeasy ADR-029), which
 * is what the executive dashboard's spend tiles actually count.
 *
 * Fire-and-forget by design: metering must never delay or fail the work that
 * produced it, so a failed report is logged and dropped, never retried into
 * the caller's latency.
 *
 * Pricing is operator configuration, not a constant baked into code — model
 * prices change and the currency is the operator's choice. Both values are
 * micro-currency units per 1000 tokens (`anthropic.pricing.*`), so the
 * defaults below are a starting point to be corrected per deployment, and the
 * cost that gets stored is the one that applied when the call happened.
 */
@Component
class MeteringReporter(
    gatewayWebClients: GatewayWebClients,
    @Value("\${zte.gateway.internal-uri:http://localhost:8080}") gatewayUri: String,
    @Value("\${zte.internal.api-key:}") internalApiKey: String,
    @Value("\${zte.metering.agent-id:zt-agents}") private val agentId: String,
    @Value("\${anthropic.pricing.input-micros-per-1k:2760}") private val inputMicrosPer1k: Long,
    @Value("\${anthropic.pricing.output-micros-per-1k:13800}") private val outputMicrosPer1k: Long
) {
    private val log = LoggerFactory.getLogger(MeteringReporter::class.java)

    private val webClient = gatewayWebClients.builder()
        .baseUrl(gatewayUri)
        .apply { builder ->
            internalApiKey.trim().takeIf { it.isNotEmpty() }
                ?.let { builder.defaultHeader("X-ZTE-Internal-Key", it) }
        }
        .build()

    fun report(model: String, inputTokens: Long, outputTokens: Long, purpose: String) {
        val costMicros =
            (inputTokens * inputMicrosPer1k / 1000) + (outputTokens * outputMicrosPer1k / 1000)
        val body = mapOf(
            "agentId" to agentId,
            "model" to model,
            "inputTokens" to inputTokens,
            "outputTokens" to outputTokens,
            "costMicros" to costMicros,
            "purpose" to purpose
        )
        webClient.post()
            .uri("/api/v1/internal/metering/llm")
            .bodyValue(body)
            .retrieve()
            .toBodilessEntity()
            .doOnError { log.warn("Metering report failed ({} tokens in/{} out): {}", inputTokens, outputTokens, it.toString()) }
            .onErrorResume { reactor.core.publisher.Mono.empty() }
            .subscribe()
    }
}
