package com.zte.gateway.policy.def;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * {@code zte.policy.*} configuration.
 *
 * <p>{@code defaultEffect} is bound as a {@link RuleEffect} enum — Spring's relaxed
 * binder rejects any value other than {@code ALLOW}/{@code DENY} at context
 * startup, giving fail-fast config validation without custom code.
 */
@Component
@ConfigurationProperties(prefix = "zte.policy")
public class PolicyDefaultsProperties {

    /** Fallback effect when no rule (YAML or DB) matches a request. Deny-by-default. */
    private RuleEffect defaultEffect = RuleEffect.DENY;

    /** Location of the YAML policy file — {@code classpath:} or {@code file:}. */
    private Resource file;

    /**
     * The OAuth2 client id that represents interactive human users (the ROPC
     * client). Any other {@code azp} on an incoming JWT is treated as a service
     * principal and governed by {@code ServiceToServiceAuthorizationFilter}
     * instead of the users2service check.
     */
    private String userClientId = "zte-gateway";

    /**
     * Every OIDC client whose tokens carry a PERSON rather than a machine (ADR-039).
     *
     * <p>{@link #userClientId} was a single value, and that was a latent bug: any
     * other {@code azp} — including the browser SPAs — was classified as a service
     * principal and sent down the service-to-service path, where a human's roles
     * are not consulted. It never showed because the Admin Console and Approval
     * Center only call gateway-local paths, which are decided by
     * {@code AdminAuthorizationFilter} instead. The chat console is the first SPA
     * to call a ROUTED path, and it was denied for being a machine.
     *
     * <p>Membership here means "tokens from this client identify a human"; it grants
     * nothing by itself, since the person's own roles still have to match a rule.
     */
    private java.util.List<String> userClientIds = java.util.List.of(
            "zte-gateway", "zte-admin-ui", "zte-approver-ui", "zte-chat-ui");

    public RuleEffect getDefaultEffect() {
        return defaultEffect;
    }

    public void setDefaultEffect(RuleEffect defaultEffect) {
        this.defaultEffect = defaultEffect;
    }

    public Resource getFile() {
        return file;
    }

    public void setFile(Resource file) {
        this.file = file;
    }

    public String getUserClientId() {
        return userClientId;
    }

    public void setUserClientId(String userClientId) {
        this.userClientId = userClientId;
    }

    public java.util.List<String> getUserClientIds() {
        return userClientIds;
    }

    public void setUserClientIds(java.util.List<String> userClientIds) {
        this.userClientIds = userClientIds;
    }

    /** True when tokens from this {@code azp} carry a person (ADR-039). */
    public boolean isUserClient(String azp) {
        return azp != null && (azp.equals(userClientId) || userClientIds.contains(azp));
    }
}
