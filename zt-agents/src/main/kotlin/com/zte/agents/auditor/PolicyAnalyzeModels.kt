package com.zte.agents.auditor

import com.fasterxml.jackson.databind.JsonNode

/**
 * Request/response contract for the structured audit endpoint (Stage 31,
 * ADR-031). The gateway SENDS the policy document here — the reverse of
 * [PolicyAuditorService.runAudit]'s fetch-from-gateway flow — so this works
 * in deployments where this service does not trust the gateway's dev CA
 * (the ADR-027 cloud topology).
 */
data class AnalyzeRequest(val policies: JsonNode)

/**
 * One structured finding. [suggestedAction] drives the Admin Console's
 * Implement button: only DISABLE_RULE is directly actionable (via the
 * activation toggle); everything else is guidance for a human editing the
 * policy file.
 */
data class AuditFinding(
    val severity: String,          // HIGH | MEDIUM | LOW
    val title: String,
    val ruleIds: List<String> = emptyList(),
    val recommendation: String,
    val suggestedAction: String = "NONE",   // DISABLE_RULE | MODIFY_RULE | ADD_RULE | NONE
    val suggestedYaml: String? = null
)

/**
 * [parseError] is the honesty flag: when the model's output could not be
 * parsed as findings, we return the raw text and say so — never a fabricated
 * empty "all clear".
 */
data class AnalyzeResponse(
    val model: String,
    val findings: List<AuditFinding>,
    val raw: String,
    val parseError: Boolean
)
