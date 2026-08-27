package com.zte.agents.client.model

import com.fasterxml.jackson.annotation.JsonProperty

data class AnthropicRequest(
    val model: String,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<Message>
) {
    data class Message(val role: String, val content: String)
}

data class AnthropicResponse(
    val id: String,
    val type: String,
    val content: List<ContentBlock>,
    // Token counts the API reports for this call — the source of the real
    // figures behind ZTeasy's spend dashboard (ADR-029). Nullable because
    // this client must keep working against any response shape that omits it.
    val usage: Usage? = null
) {
    data class ContentBlock(val type: String, val text: String?)

    data class Usage(
        @JsonProperty("input_tokens") val inputTokens: Long = 0,
        @JsonProperty("output_tokens") val outputTokens: Long = 0
    )

    fun textContent(): String = content
        .filter { it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("\n")
}
