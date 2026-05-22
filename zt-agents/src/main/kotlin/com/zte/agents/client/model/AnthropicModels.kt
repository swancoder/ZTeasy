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
    val content: List<ContentBlock>
) {
    data class ContentBlock(val type: String, val text: String?)

    fun textContent(): String = content
        .filter { it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("\n")
}
