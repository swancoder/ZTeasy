package com.zte.gateway.approver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime UI configuration (ADR-026/ADR-027): a one-line JavaScript snippet
 * both SPAs ({@code /admin/**} and {@code /approver/**}) load via
 * {@code <script src="/ui-config.js">} before their bundle, defining the OIDC
 * authority to log in against.
 *
 * <p>Why a controller and not a baked-in constant: the same built bundle must
 * work against a directly-reachable local Keycloak
 * ({@code http://localhost:8180/realms/zte-realm}, the default) <em>and</em>
 * a deployment where Keycloak is reverse-proxied under the gateway's own
 * origin ({@code /auth/realms/zte-realm}, the Azure topology — ADR-027).
 * The value is an env-var-overridable Spring property, so switching
 * environments is a config change, not a rebuild.
 *
 * <p>permitAll (see {@link ApproverUiConfig}) — the snippet tells an
 * unauthenticated browser where to authenticate; it contains only that URL.
 */
@RestController
class UiConfigController {

    private final String oidcAuthority;

    UiConfigController(@Value("${zte.ui.oidc-authority:http://localhost:8180/realms/zte-realm}") String oidcAuthority) {
        this.oidcAuthority = oidcAuthority;
    }

    @GetMapping(value = "/ui-config.js", produces = "text/javascript")
    public String uiConfig() {
        // Single-quoted, JSON-escaped via replace of the only two characters a
        // URL property could realistically smuggle in — this is operator
        // config, not user input, but escape anyway.
        String escaped = oidcAuthority.replace("\\", "\\\\").replace("'", "\\'");
        return "window.ZTE_OIDC_AUTHORITY = '" + escaped + "';\n";
    }
}
