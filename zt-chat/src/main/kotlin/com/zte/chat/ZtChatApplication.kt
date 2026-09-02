package com.zte.chat

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The chat console's backend (Stage 39, ADR-039).
 *
 * <p>It runs the tool-calling loop on behalf of a person: the browser never holds a
 * model credential, a client certificate, or the ability to call a tool. Everything
 * this service does on the user's behalf goes back through the ZTeasy gateway —
 * the model call and every tool call alike — so a chat message is governed by the
 * same policy engine, the same ACAP scope and the same audit trail as an agent.
 *
 * <p>Deliberately *not* given the ADR-038 hop certificate: this is a client of the
 * gate, not a peer of it. If it could reach the MCP backend directly it would be
 * the bypass this system exists to prevent.
 */
@SpringBootApplication
class ZtChatApplication

fun main(args: Array<String>) {
    runApplication<ZtChatApplication>(*args)
}
