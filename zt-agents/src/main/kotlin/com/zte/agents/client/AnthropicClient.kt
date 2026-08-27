package com.zte.agents.client

import com.zte.agents.client.model.AnthropicRequest
import com.zte.agents.client.model.AnthropicResponse
import com.zte.agents.metering.MeteringReporter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * WebClient-based client for the Anthropic Messages API.
 *
 * Sends a complete prompt and waits for the full response (non-streaming).
 * A 120-second timeout accommodates LLM latency without blocking server threads —
 * WebFlux suspends the reactive chain until Anthropic responds.
 *
 * API reference: https://docs.anthropic.com/en/api/messages
 */
@Component
class AnthropicClient(
    private val metering: MeteringReporter,
    @Value("\${anthropic.api-key}") private val apiKey: String,
    @Value("\${anthropic.model:claude-sonnet-4-6}") private val model: String,
    @Value("\${anthropic.max-tokens:4096}") private val maxTokens: Int,
    @Value("\${anthropic.timeout-seconds:120}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(AnthropicClient::class.java)

    private val webClient = WebClient.builder()
        .baseUrl("https://api.anthropic.com")
        .defaultHeader("x-api-key", apiKey)
        .defaultHeader("anthropic-version", "2023-06-01")
        .build()

    fun complete(systemPrompt: String, userMessage: String): Mono<String> {
        val request = AnthropicRequest(
            model    = model,
            maxTokens = maxTokens,
            system   = systemPrompt,
            messages = listOf(AnthropicRequest.Message(role = "user", content = userMessage))
        )
        log.info("Sending audit request to Anthropic (model={}, max_tokens={})", model, maxTokens)
        return webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(AnthropicResponse::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // Report token spend before mapping the body away — the usage
            // block is the only place the real numbers exist (ZTeasy ADR-029).
            .doOnNext { response ->
                response.usage?.let {
                    metering.report(model, it.inputTokens, it.outputTokens, "policy-audit")
                }
            }
            .map { it.textContent() }
            .doOnSuccess { log.info("Anthropic audit complete ({} chars)", it.length) }
    }
}
