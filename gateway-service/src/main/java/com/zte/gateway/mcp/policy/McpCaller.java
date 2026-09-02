package com.zte.gateway.mcp.policy;

import com.zte.gateway.identity.IdentitySources;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is making an MCP tool call (Stage 39, ADR-039).
 *
 * <p>Until now the answer was always "an agent": {@code evaluate(agentId, ...)}
 * derived its sources with {@link IdentitySources#enrichClient}, and an ACAP
 * profile was found by that one client id. That was the whole vocabulary, so a
 * human using a chat client would have been governed as the chat client — every
 * user sharing one identity, one scope profile and one set of rules, with the
 * person visible only in the audit trail. The per-user story would have been a
 * label on a shared decision.
 *
 * <p>This type keeps the two cases distinct where it matters and identical where
 * it does not:
 * <ul>
 *   <li>{@code sources} — what the policy matcher sees. An agent contributes
 *       {@code client:<id>}; a human contributes {@code user:<name>}, their
 *       {@code role:} URNs, <em>and</em> the client they came through, so a rule
 *       can name the person, the role, or the application.</li>
 *   <li>{@code acapKeys} — the profile lookup order. An agent has one key; a
 *       human is looked up by username first, then by each role, so scope can be
 *       written once for "sales in EMEA" instead of per person.</li>
 * </ul>
 */
public record McpCaller(String id, String display, List<String> sources, List<String> acapKeys, boolean human) {

    /** An agent authenticating with client credentials — the ADR-010 path, unchanged. */
    public static McpCaller client(String clientId) {
        return new McpCaller(clientId, clientId, IdentitySources.enrichClient(clientId), List.of(clientId), false);
    }

    /**
     * A person, arriving through an application that authenticated itself
     * separately (ADR-039). The application's own client id is included as a
     * source so a rule may require that a human arrives through a specific
     * front door — but it is deliberately last, because the subject of the
     * decision is the person.
     */
    public static McpCaller user(String username, List<String> realmRoles, String viaClientId) {
        List<String> sources = new ArrayList<>();
        sources.add("user:" + username);
        List<String> keys = new ArrayList<>();
        keys.add("user:" + username);
        for (String role : realmRoles == null ? List.<String>of() : realmRoles) {
            sources.add("role:" + role);
            sources.add(role);          // bare form, same as ADR-014's rule vocabulary
            keys.add("role:" + role);
        }
        if (viaClientId != null && !viaClientId.isBlank()) {
            sources.add("client:" + viaClientId);
        }
        return new McpCaller(username, username, List.copyOf(sources), List.copyOf(keys), true);
    }
}
