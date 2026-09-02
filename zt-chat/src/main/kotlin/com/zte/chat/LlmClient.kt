package com.zte.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * Calls the model through the gateway (ADR-039).
 *
 * <p>This service holds no vendor credential. It cannot: the key lives on the
 * gateway, which injects it, meters the tokens out of the vendor's own response and
 * attributes the spend to the person whose token is on the request. A compromised
 * chat backend therefore leaks no model credential and can spend nothing the
 * perimeter did not authorise and count.
 */
@Component
class LlmClient(
    private val gateway: WebClient,
    private val mapper: ObjectMapper,
    @Value("\${zte.llm.model:claude-sonnet-4-6}") private val model: String,
    @Value("\${zte.llm.max-tokens:2048}") private val maxTokens: Int
) {

    fun complete(userToken: String, messages: JsonNode, tools: JsonNode, system: String): Mono<JsonNode> {
        val body = mapper.createObjectNode().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            set<JsonNode>("messages", messages)
            if (tools.isArray && tools.size() > 0) set<JsonNode>("tools", tools)
        }
        return gateway.post()
            .uri("/api/v1/llm/messages")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
    }
}
