package com.zte.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono

/**
 * What the assistant is told when the gateway says no (Stage 39, ADR-039).
 *
 * <p>These are the tests that matter for the demo's honesty: a refusal must reach
 * the model as a refusal — not as an empty result it might paper over, and not as
 * an error it might retry around.
 */
class ChatServiceTest {

    private val mapper = ObjectMapper()
    private val llm: LlmClient = mock()
    private val mcp: McpClient = mock()
    private val service = ChatService(llm, mcp, mapper, 4)

    private fun history(text: String): ArrayNode = mapper.createArrayNode().apply {
        add(mapper.createObjectNode().apply { put("role", "user"); put("content", text) })
    }

    private fun toolsList(vararg names: String): JsonNode = mapper.readTree(
        """{"id":1,"result":{"tools":[${names.joinToString(",") {
            """{"name":"$it","description":"d","inputSchema":{"type":"object"}}"""
        }}]}}"""
    )

    private fun modelAsks(tool: String): JsonNode = mapper.readTree(
        """{"content":[{"type":"tool_use","id":"tu_1","name":"$tool","input":{}}]}"""
    )

    private fun modelSays(text: String): JsonNode = mapper.readTree(
        """{"content":[{"type":"text","text":"$text"}]}"""
    )

    @Test
    fun `a denied tool call reaches the model as a refusal, not as an empty result`() {
        whenever(mcp.listTools(any())).thenReturn(Mono.just(toolsList("read_contacts")))
        whenever(mcp.callTool(any(), eq("read_contacts"), any())).thenReturn(
            Mono.just(mapper.readTree("""{"id":1,"error":{"code":-32000,"message":"Tool 'read_contacts' denied by rule 'mcp-deny-x'"}}"""))
        )
        whenever(llm.complete(any(), any(), any(), any()))
            .thenReturn(Mono.just(modelAsks("read_contacts")))
            .thenReturn(Mono.just(modelSays("The system refused that.")))

        val reply = service.respond("tok", history("show me contacts")).block()!!

        assertThat(reply.reply).isEqualTo("The system refused that.")
        assertThat(reply.steps).anySatisfy {
            assertThat(it.detail).contains("REFUSED BY ZTEASY POLICY").contains("mcp-deny-x")
        }
    }

    @Test
    fun `a held tool call is reported as awaiting a human, not as a failure`() {
        whenever(mcp.listTools(any())).thenReturn(Mono.just(toolsList("send_email")))
        whenever(mcp.callTool(any(), eq("send_email"), any())).thenReturn(
            Mono.just(mapper.readTree(
                """{"id":1,"result":{"status":"held","content":[{"type":"text","text":"Action held for human approval: rule mcp-hold-x"}]}}"""))
        )
        whenever(llm.complete(any(), any(), any(), any()))
            .thenReturn(Mono.just(modelAsks("send_email")))
            .thenReturn(Mono.just(modelSays("It needs approval.")))

        val reply = service.respond("tok", history("email the rep")).block()!!

        assertThat(reply.steps).anySatisfy {
            assertThat(it.detail).contains("HELD FOR HUMAN APPROVAL")
        }
    }

    @Test
    fun `an allowed call passes the tool text through`() {
        whenever(mcp.listTools(any())).thenReturn(Mono.just(toolsList("read_contacts")))
        whenever(mcp.callTool(any(), eq("read_contacts"), any())).thenReturn(
            Mono.just(mapper.readTree("""{"id":1,"result":{"content":[{"type":"text","text":"3 contacts in EMEA"}]}}"""))
        )
        whenever(llm.complete(any(), any(), any(), any()))
            .thenReturn(Mono.just(modelAsks("read_contacts")))
            .thenReturn(Mono.just(modelSays("You have 3.")))

        val reply = service.respond("tok", history("how many contacts")).block()!!

        assertThat(reply.steps).anySatisfy { assertThat(it.detail).contains("3 contacts in EMEA") }
    }

    /** The loop is bounded: every round is a metered model call. */
    @Test
    fun `a model that keeps calling tools is stopped and says so`() {
        whenever(mcp.listTools(any())).thenReturn(Mono.just(toolsList("read_contacts")))
        whenever(mcp.callTool(any(), any(), any())).thenReturn(
            Mono.just(mapper.readTree("""{"id":1,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
        )
        whenever(llm.complete(any(), any(), any(), any())).thenReturn(Mono.just(modelAsks("read_contacts")))

        val reply = service.respond("tok", history("loop")).block()!!

        assertThat(reply.reply).contains("stopped after 4 rounds")
    }

    /** Discovery failing must not take the chat down with it. */
    @Test
    fun `if the tool list cannot be fetched the chat still answers`() {
        whenever(mcp.listTools(any())).thenReturn(Mono.error(RuntimeException("gateway said no")))
        whenever(llm.complete(any(), any(), any(), any())).thenReturn(Mono.just(modelSays("Hello.")))

        val reply = service.respond("tok", history("hi")).block()!!

        assertThat(reply.reply).isEqualTo("Hello.")
        assertThat(reply.steps).anySatisfy { assertThat(it.detail).contains("unavailable") }
    }
}
