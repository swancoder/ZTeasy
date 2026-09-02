package com.zte.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Calls MCP tools *through* the ZTeasy gateway, as the person in the chat (ADR-039).
 *
 * <p>The transport is the one ADR-009 defined for agents and is deliberately not
 * simplified for this caller: open `GET /sse`, read the handshake event for a
 * session id, `POST /message?sessionId=…` (which always answers 202), and read the
 * real result off the event stream. A convenience path that skipped it would be a
 * second door into the same room, and the point of this service is that a person
 * goes through the same one an agent does.
 *
 * <p>One stream per call. A pooled long-lived session would be fewer connections
 * and one more piece of state to get wrong; at chat pace the connection is not the
 * expensive part — the model is.
 */
@Component
class McpClient(
    private val gateway: WebClient,
    private val mapper: ObjectMapper,
    @Value("\${zte.mcp.timeout-seconds:60}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(McpClient::class.java)
    private val ids = AtomicInteger(1)

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}

    fun listTools(userToken: String): Mono<JsonNode> =
        exchange(userToken, mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("method", "tools/list")
            set<JsonNode>("params", mapper.createObjectNode())
        })

    fun callTool(userToken: String, name: String, arguments: JsonNode): Mono<JsonNode> =
        exchange(userToken, mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("method", "tools/call")
            set<JsonNode>("params", mapper.createObjectNode().apply {
                put("name", name)
                set<JsonNode>("arguments", arguments)
            })
        })

    private fun exchange(userToken: String, rpc: com.fasterxml.jackson.databind.node.ObjectNode): Mono<JsonNode> {
        val id = ids.getAndIncrement()
        rpc.put("id", id)

        return Mono.create<JsonNode> { sink ->
            var subscription: Disposable? = null
            subscription = gateway.get()
                .uri("/sse")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .bodyToFlux(sseType)
                .subscribe(
                    { event ->
                        val data = event.data() ?: return@subscribe
                        if (event.event() == "endpoint") {
                            val sessionId = SESSION_ID.find(data)?.groupValues?.get(1)
                            if (sessionId == null) {
                                sink.error(IllegalStateException("no sessionId in handshake: $data"))
                            } else {
                                post(userToken, sessionId, rpc).subscribe({}, sink::error)
                            }
                            return@subscribe
                        }
                        val json = runCatching { mapper.readTree(data) }.getOrNull() ?: return@subscribe
                        // The gateway answers DENY, HOLD and ALLOW on the same stream;
                        // correlating by id keeps this honest about which call was answered.
                        if (json.path("id").asInt(-1) == id) {
                            sink.success(json)
                        }
                    },
                    sink::error
                )
            sink.onDispose { subscription?.dispose() }
        }
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .doOnError { e -> log.warn("[ZTE-CHAT] MCP exchange failed: {}", e.toString()) }
    }

    private fun post(userToken: String, sessionId: String, rpc: JsonNode): Mono<Void> =
        gateway.post()
            .uri { b -> b.path("/message").queryParam("sessionId", sessionId).build() }
            .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
            .bodyValue(rpc)
            .retrieve()
            .bodyToMono(Void::class.java)

    private companion object {
        val SESSION_ID = Regex("sessionId=([\\w-]+)")
    }
}
