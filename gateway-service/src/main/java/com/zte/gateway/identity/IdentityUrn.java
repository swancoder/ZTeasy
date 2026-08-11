package com.zte.gateway.identity;

import java.util.Optional;

/**
 * Parsed form of a policy rule's {@code source} field —
 * {@code user:<name>}/{@code group:<name>}/{@code role:<name>} (ADR-014) plus
 * {@code client:<clientId>} (ADR-015), or (backward compat) a bare name,
 * whose implied type depends on which category is asking:
 * {@code users2service} implies {@link IdentityType#ROLE} (ADR-014's
 * original bare-role-name convention); {@code service2service}/
 * {@code agentMcpToolCalls} imply {@link IdentityType#CLIENT} (ADR-015 —
 * every pre-ADR-015 rule in those two categories was already a bare OAuth2
 * client id, so this is the only backward-compatible default).
 *
 * <p>An unrecognized prefix is not an error: the whole string is treated as
 * a literal name of the caller-supplied default type, same as no prefix at
 * all — a rule author who typos {@code rle:ADMIN} gets a role named
 * {@code "rle:ADMIN"} (which will then legitimately show up as orphaned)
 * rather than a silently-ignored rule.
 */
public record IdentityUrn(IdentityType type, String name) {

    private static final String USER_PREFIX = "user:";
    private static final String GROUP_PREFIX = "group:";
    private static final String ROLE_PREFIX = "role:";
    private static final String CLIENT_PREFIX = "client:";

    /** Equivalent to {@code parse(source, IdentityType.ROLE)} — the {@code users2service} default (ADR-014). */
    public static Optional<IdentityUrn> parse(String source) {
        return parse(source, IdentityType.ROLE);
    }

    /**
     * @param defaultType the type implied by a bare (no-prefix) source — the
     *                     caller decides this per policy category; an explicit
     *                     prefix always overrides it regardless.
     * @return {@code Optional.empty()} for wildcard sources ({@code *}/{@code ?}) — not checkable against a fixed identity list.
     */
    public static Optional<IdentityUrn> parse(String source, IdentityType defaultType) {
        if (source == null || source.isEmpty() || containsWildcard(source)) {
            return Optional.empty();
        }
        if (source.startsWith(USER_PREFIX)) {
            return Optional.of(new IdentityUrn(IdentityType.USER, source.substring(USER_PREFIX.length())));
        }
        if (source.startsWith(GROUP_PREFIX)) {
            return Optional.of(new IdentityUrn(IdentityType.GROUP, source.substring(GROUP_PREFIX.length())));
        }
        if (source.startsWith(ROLE_PREFIX)) {
            return Optional.of(new IdentityUrn(IdentityType.ROLE, source.substring(ROLE_PREFIX.length())));
        }
        if (source.startsWith(CLIENT_PREFIX)) {
            return Optional.of(new IdentityUrn(IdentityType.CLIENT, source.substring(CLIENT_PREFIX.length())));
        }
        return Optional.of(new IdentityUrn(defaultType, source));
    }

    private static boolean containsWildcard(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }
}
