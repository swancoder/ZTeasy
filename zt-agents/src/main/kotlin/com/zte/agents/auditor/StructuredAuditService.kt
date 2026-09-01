package com.zte.agents.auditor

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.zte.agents.client.AnthropicClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

private const val STRUCTURED_SYSTEM_PROMPT = """You are a Zero Trust security auditor \
reviewing an access-policy document for an API gateway that fronts both REST services and \
AI-agent (MCP) tool calls.

Analyze the policy JSON you are given. Look for: overly broad grants (wildcards, permissive \
paths/sources), violations of least privilege, missing deny coverage, rules that contradict \
each other, and MCP tool grants that look dangerous for an autonomous agent.

Respond with ONLY a JSON object, no markdown, no prose, exactly this shape:
{"findings":[{"severity":"HIGH|MEDIUM|LOW","title":"short title","ruleIds":["ids of the \
affected rules from the document, empty if none"],"recommendation":"what to do and why, \
2-4 sentences","suggestedAction":"DISABLE_RULE|MODIFY_RULE|ADD_RULE|NONE","suggestedYaml":"\
the corrected or new rule as YAML, or null"}]}

Rules for suggestedAction: use DISABLE_RULE only when the safest immediate step is to \
deactivate an existing rule (then ruleIds must name exactly that rule); MODIFY_RULE when a \
rule should be edited (put the full corrected rule in suggestedYaml); ADD_RULE when \
coverage is missing (put the new rule in suggestedYaml); NONE for observations. \
Reference ONLY rule ids that actually appear in the document. 3 to 8 findings, most severe \
first."""

/**
 * The structured half of the Policy Auditor (Stage 31, ADR-031): policies in,
 * findings out. Kept separate from [PolicyAuditorService] — the Markdown
 * report endpoint keeps its contract; the Admin Console consumes this one.
 */
@Service
class StructuredAuditService(
    private val anthropicClient: AnthropicClient,
    @Value("\${anthropic.model:claude-sonnet-4-6}") private val model: String
) {
    private val log = LoggerFactory.getLogger(StructuredAuditService::class.java)

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun analyze(request: AnalyzeRequest): Mono<AnalyzeResponse> {
        val userMessage = "Audit this policy document:\n" +
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(request.policies)
        log.info("Structured policy audit started ({} chars of policy JSON)", userMessage.length)
        return anthropicClient.complete(STRUCTURED_SYSTEM_PROMPT, userMessage)
            .map { raw -> parse(raw) }
            .doOnSuccess { log.info("Structured audit complete: {} findings (parseError={})", it.findings.size, it.parseError) }
    }

    /**
     * Defensive parse: models occasionally wrap JSON in code fences or lead
     * with prose despite instructions. We strip fences, then locate the
     * outermost object. Anything unparseable degrades to parseError=true with
     * the raw text preserved — the UI shows the text instead of pretending
     * there were no findings.
     */
    private fun parse(raw: String): AnalyzeResponse {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return AnalyzeResponse(model, emptyList(), raw, parseError = true)
        }
        return try {
            val body: FindingsEnvelope = mapper.readValue(cleaned.substring(start, end + 1))
            AnalyzeResponse(model, body.findings.map(::normalize), raw, parseError = false)
        } catch (e: Exception) {
            log.warn("Audit response did not parse as findings: {}", e.toString())
            AnalyzeResponse(model, emptyList(), raw, parseError = true)
        }
    }

    private fun normalize(f: AuditFinding): AuditFinding {
        val severity = f.severity.uppercase().takeIf { it in setOf("HIGH", "MEDIUM", "LOW") } ?: "MEDIUM"
        val action = f.suggestedAction.uppercase()
            .takeIf { it in setOf("DISABLE_RULE", "MODIFY_RULE", "ADD_RULE", "NONE") } ?: "NONE"
        return f.copy(severity = severity, suggestedAction = action)
    }

    internal data class FindingsEnvelope(val findings: List<AuditFinding> = emptyList())
}
