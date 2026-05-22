package com.zte.agents.client.model

data class PolicyDto(
    val id: Long?,
    val roleName: String,
    val pathPattern: String,
    val methods: String,
    val enabled: Boolean
) {
    fun toAuditLine(): String =
        "- role=`$roleName`  path=`$pathPattern`  methods=`$methods`  enabled=$enabled"
}
