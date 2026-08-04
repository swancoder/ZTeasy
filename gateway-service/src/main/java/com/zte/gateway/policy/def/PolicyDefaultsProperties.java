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
}
