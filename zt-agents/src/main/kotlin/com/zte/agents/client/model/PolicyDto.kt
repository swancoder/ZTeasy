package com.zte.agents.client.model

/** Mirrors the gateway's YAML `PolicyRule` shape (ADR-011/ADR-012) — the
 * `users2service` rules returned by `GET /api/v1/internal/policies`. */
data class PolicyDto(
    val id: String,
    val effect: String,
    val source: String,
    val target: String,
    val pathPattern: String?,
    val methods: String?,
    val priority: Int
) {
    fun toAuditLine(): String =
        "- id=`$id`  effect=$effect  source=`$source`  target=`$target`  path=`$pathPattern`  methods=`$methods`  priority=$priority"
}
