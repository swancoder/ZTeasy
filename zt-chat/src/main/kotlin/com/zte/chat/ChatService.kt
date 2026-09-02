package com.zte.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * The tool-calling loop, run for a person (Stage 39, ADR-039).
 *
 * <p>The model is offered every tool the MCP backend exposes and finds out from the
 * gate which ones this person may actually use. That is deliberate: filtering the
 * menu first would hide the governance instead of demonstrating it, and — more
 * importantly — it would teach the model that whatever it can see, it may do. Here
 * a refusal comes back as a tool result the model must read and explain, which is
 * exactly what should happen when a policy stops someone.
 */
@Service
class ChatService(
    private val llm: LlmClient,
    private val mcp: McpClient,
    private val mapper: ObjectMapper,
    @Value("\${zte.chat.max-tool-rounds:4}") private val maxRounds: Int,
    // Empty by default since stage 42 retired the pre-ACAP tool surface; the mechanism
    // stays for a deployment whose backend still advertises superseded names.
    @Value("\${zte.chat.superseded-tools:}")
    private val superseded: String
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    private val system = """
        You are a CRM assistant inside ZTeasy, a Zero Trust gateway demo.
        You have CRM tools available. Use them when the user asks for CRM data or actions.

        When reading, pass the territory and the specific fields you need — the gateway
        scopes both, and asking for everything is how you get refused.

        Some calls will be refused by policy, or held for a human approval. When that
        happens, tell the user plainly what was refused and the reason the system gave.
        Never work around a refusal: do not retry the same call with different arguments
        hoping it passes, and do not use a different tool to obtain the same data. A
        refusal is an answer, and reporting it accurately is the most useful thing you
        can do.
    """.trimIndent()

    /**
     * @param userToken the person's own access token — every hop this method makes is
     *                  made as them, which is what puts their name on the decision and
     *                  on the bill.
     */
    fun respond(userToken: String, history: ArrayNode): Mono<ChatReply> {
        val steps = mutableListOf<Step>()
        return mcp.listTools(userToken)
            .map { toolSchema(it) }
            .onErrorResume { e ->
                // A chat that cannot see the tools is still a chat; say so rather than fail.
                log.warn("[ZTE-CHAT] tool discovery failed: {}", e.toString())
                steps += Step("discovery", "tools/list", "unavailable: ${e.message}")
                Mono.just(mapper.createArrayNode())
            }
            .flatMap { tools -> round(userToken, history, tools, steps, 0) }
            .map { text -> ChatReply(text, steps) }
    }

    private fun round(userToken: String, messages: ArrayNode, tools: JsonNode,
                       steps: MutableList<Step>, depth: Int): Mono<String> {
        if (depth >= maxRounds) {
            // A bounded loop: a model that keeps calling tools forever would spend real
            // money doing it, and the gateway meters every round.
            return Mono.just("I stopped after $maxRounds rounds of tool calls without reaching an answer.")
        }
        return llm.complete(userToken, messages, tools, system).flatMap { response ->
            val content = response.path("content")
            val text = content.filter { it.path("type").asText() == "text" }
                .joinToString("\n") { it.path("text").asText() }
            val toolUses = content.filter { it.path("type").asText() == "tool_use" }

            if (toolUses.isEmpty()) {
                return@flatMap Mono.just(if (text.isBlank()) "(no answer)" else text)
            }

            messages.add(mapper.createObjectNode().apply {
                put("role", "assistant")
                set<JsonNode>("content", content)
            })

            val results = mapper.createArrayNode()
            var chain: Mono<Void> = Mono.empty()
            for (use in toolUses) {
                val name = use.path("name").asText()
                val args = use.path("input")
                val useId = use.path("id").asText()
                chain = chain.then(
                    mcp.callTool(userToken, name, args)
                        .map { rpc -> renderToolResult(rpc) }
                        .onErrorResume { e -> Mono.just("The gateway did not answer: ${e.message}") }
                        .doOnNext { rendered ->
                            steps += Step("tool", name, rendered.take(400))
                            results.add(mapper.createObjectNode().apply {
                                put("type", "tool_result")
                                put("tool_use_id", useId)
                                put("content", rendered)
                            })
                        }
                        .then()
                )
            }

            chain.then(Mono.defer {
                messages.add(mapper.createObjectNode().apply {
                    put("role", "user")
                    set<JsonNode>("content", results)
                })
                round(userToken, messages, tools, steps, depth + 1)
            })
        }
    }

    /**
     * Turns the gateway's answer into something the model can act on honestly. A
     * denial and a held call are not errors to be papered over — they are the
     * result, and the model is told exactly that.
     */
    private fun renderToolResult(rpc: JsonNode): String {
        val error = rpc.path("error")
        if (!error.isMissingNode && !error.isNull) {
            return "REFUSED BY ZTEASY POLICY: ${error.path("message").asText(error.toString())}"
        }
        val result = rpc.path("result")
        if (result.path("status").asText() == "held") {
            return "HELD FOR HUMAN APPROVAL by ZTeasy: ${textOf(result)}"
        }
        return textOf(result).ifBlank { result.toString() }
    }

    private fun textOf(result: JsonNode): String =
        result.path("content").filter { it.path("type").asText() == "text" }
            .joinToString("\n") { it.path("text").asText() }

    /**
     * Anthropic tool schema from the MCP tools/list result.
     *
     * <p>Superseded tools are **annotated, not removed**. Filtering them out would
     * hide the governance the console exists to show — and a model that never sees a
     * forbidden tool never demonstrates being refused one. But a model picks a tool
     * by reading its description, so that is where the warning belongs: telling it in
     * the system prompt and leaving the menu unmarked was advice in the wrong place,
     * and it went on choosing `get_contacts` for "show me my clients" (ADR-039).
     */
    private fun toolSchema(rpc: JsonNode): ArrayNode {
        val tools = mapper.createArrayNode()
        for (tool in rpc.path("result").path("tools")) {
            val name = tool.path("name").asText()
            val replacement = supersededBy[name]
            val description = tool.path("description").asText("").let {
                if (replacement == null) it
                else "DEPRECATED — this tool is not covered by ZTeasy's scope profiles and every " +
                        "call to it is refused by policy. Use `$replacement` instead. ($it)"
            }
            (mapper.createObjectNode() as ObjectNode).apply {
                put("name", name)
                put("description", description)
                set<JsonNode>("input_schema", tool.path("inputSchema").let {
                    if (it.isMissingNode || it.isNull) mapper.createObjectNode().put("type", "object") else it
                })
                tools.add(this)
            }
        }
        return tools
    }

    /**
     * The pre-ACAP tool surface the backend still advertises, mapped to what replaced
     * it. Configured rather than hard-coded so a deployment fronting a different
     * backend can describe its own legacy names.
     */
    private val supersededBy: Map<String, String> = superseded.split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    /** One thing the assistant did, for the trace panel next to the conversation. */
    data class Step(val kind: String, val name: String, val detail: String)

    data class ChatReply(val reply: String, val steps: List<Step>)
}
