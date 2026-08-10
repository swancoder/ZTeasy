package com.zte.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured security-event logger for ZTE services.
 *
 * <p>All security decisions (mTLS handshake outcome, OBO token validation,
 * policy evaluation) are emitted in a consistent line format so they can be
 * grep'd, tailed, or ingested by a log aggregator without additional parsing:
 *
 * <pre>
 * [ZTE-AUDIT] event=MTLS_ACCEPTED  service=service-a  detail=CN=zte-internal-client
 * [ZTE-AUDIT] event=OBO_VALIDATED  service=service-b  detail=sub=abc-123 roles=[ADMIN]
 * [ZTE-AUDIT] event=OBO_REJECTED   service=service-b  detail=JWT expired at 2026-04-21T...
 * [ZTE-AUDIT] event=POLICY_ALLOW   service=gateway    detail=roles=[ADMIN] GET /api/v1/service-a/hello
 * [ZTE-AUDIT] event=POLICY_DENY    service=gateway    detail=roles=[USER]  GET /api/v1/service-a/hello
 * </pre>
 *
 * <p>Usage — static calls, no DI required:
 * <pre>{@code
 * ZteAuditLogger.mtlsAccepted("service-b", clientCn);
 * ZteAuditLogger.oboValidated("service-b", claims.sub(), claims.roles().toString());
 * ZteAuditLogger.oboRejected("service-b", e.getMessage());
 * ZteAuditLogger.policyAllow("gateway", roles.toString(), method + " " + path);
 * ZteAuditLogger.policyDeny("gateway",  roles.toString(), method + " " + path);
 * }</pre>
 *
 * <p>Output routes to whatever SLF4J appender the consuming service configures
 * (Logback JSON, stdout, file). No persistence, no async queue — demo-grade.
 */
public final class ZteAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("ZTE-AUDIT");

    private static final String FMT = "[ZTE-AUDIT] event={:<16} service={} detail={}";

    private ZteAuditLogger() {}

    /** mTLS handshake succeeded — client certificate accepted by server. */
    public static void mtlsAccepted(String service, String clientCn) {
        log.info(FMT, "MTLS_ACCEPTED", service, "CN=" + clientCn);
    }

    /** X-ZTE-User-Context OBO token validated successfully. */
    public static void oboValidated(String service, String sub, String roles) {
        log.info(FMT, "OBO_VALIDATED", service, "sub=" + sub + " roles=" + roles);
    }

    /** X-ZTE-User-Context OBO token rejected (invalid signature, expired, missing). */
    public static void oboRejected(String service, String reason) {
        log.warn(FMT, "OBO_REJECTED", service, reason);
    }

    /** Gateway DB policy check — request allowed. */
    public static void policyAllow(String service, String roles, String request) {
        log.info(FMT, "POLICY_ALLOW", service, "roles=" + roles + " " + request);
    }

    /** Gateway DB policy check — request denied (no matching policy row). */
    public static void policyDeny(String service, String roles, String request) {
        log.warn(FMT, "POLICY_DENY", service, "roles=" + roles + " " + request);
    }

    /**
     * Unified YAML policy-engine decision — shared by every enforcement point
     * (users2service and service2service in the API gateway, agent@mcp/tool-call
     * in the MCP proxy) so all three emit byte-for-byte the same log shape,
     * queryable/filterable by subject, target, or outcome without per-proxy
     * format drift.
     */
    public static void policyDecision(String category, String subject, String target,
                                       String matchedRuleId, String outcome) {
        String detail = "category=" + category + " subject=" + subject + " target=" + target
                + " rule=" + (matchedRuleId == null ? "none" : matchedRuleId) + " outcome=" + outcome;
        if ("DENY".equals(outcome)) {
            log.warn(FMT, "POLICY_DECISION", "zte", detail);
        } else {
            log.info(FMT, "POLICY_DECISION", "zte", detail);
        }
    }

    /**
     * Synchronous SLF4J counterpart to the async, R2DBC-backed
     * {@code request_logs} write (ADR-013) — same "log both sync and async"
     * pattern already used for MCP audit events.
     */
    public static void requestLog(String traceId, String path, Integer statusCode) {
        log.info(FMT, "REQUEST_LOG", "gateway",
                "traceId=" + traceId + " path=" + path + " status=" + statusCode);
    }

    /** A policy reload was triggered (file-based, via the admin endpoint). */
    public static void policyReloadTriggered() {
        log.info(FMT, "POLICY_RELOAD_TRIGGERED", "gateway", "");
    }

    /** Policy reload succeeded — new document swapped in atomically. */
    public static void policyReloadSucceeded() {
        log.info(FMT, "POLICY_RELOAD_SUCCESS", "gateway", "");
    }

    /** Policy reload failed validation — previous document remains active. */
    public static void policyReloadFailed(String reason) {
        log.warn(FMT, "POLICY_RELOAD_FAILURE", "gateway", reason);
    }
}
