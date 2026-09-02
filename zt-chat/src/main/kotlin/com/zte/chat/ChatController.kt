package com.zte.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The chat console's only API (Stage 39, ADR-039).
 *
 * <p>Stateless on purpose: the browser owns the conversation and sends it back each
 * turn. Server-side session state would be one more thing to scale, expire and leak,
 * and it would put the user's CRM conversation in a second place — the audit trail
 * already records every governed action the conversation caused.
 */
@RestController
// The gateway's inventory routing forwards /api/v1/{name}/** to the service
// unchanged — no prefix is stripped — so this path is what a browser calls on the
// gateway and what arrives here (ADR-017).
@RequestMapping("/api/v1/chat")
class ChatController(
    private val chat: ChatService,
    private val mapper: ObjectMapper
) {

    data class Turn(val role: String, val content: String)
    data class ChatRequest(val messages: List<Turn> = emptyList())

    @PostMapping
    fun send(@RequestBody request: ChatRequest, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
        // The user's own token is relayed to the gateway for both the model call and
        // every tool call, so the decision — and the bill — is about the person, not
        // about this service (ADR-039).
        val token = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) {
            return Mono.just(ResponseEntity.status(401).body(mapOf("error" to "no bearer token to act on your behalf") as Any))
        }
        if (request.messages.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(mapOf("error" to "no messages") as Any))
        }

        val history: ArrayNode = mapper.createArrayNode()
        request.messages.forEach { turn ->
            history.add(mapper.createObjectNode().apply {
                put("role", if (turn.role == "assistant") "assistant" else "user")
                put("content", turn.content)
            })
        }

        return chat.respond(token, history)
            .map { ResponseEntity.ok(it as Any) }
            .onErrorResume { e ->
                Mono.just(ResponseEntity.status(502).body(mapOf("error" to (e.message ?: e.toString())) as Any))
            }
    }
}
