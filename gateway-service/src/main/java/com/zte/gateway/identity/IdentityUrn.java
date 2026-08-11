package com.zte.gateway.identity;

import java.util.Optional;

/**
 * Parsed form of a {@code users2service} rule's {@code source} field
 * (ADR-014) — {@code user:<name>}, {@code group:<name>}, {@code role:<name>},
 * or (backward compat) a bare name, implicitly a role.
 *
 * <p>An unrecognized prefix is not an error: the whole string is treated as
 * a literal role name, same as no prefix at all — a rule author who typos
 * {@code rle:ADMIN} gets a role named {@code "rle:ADMIN"} (which will then
 * legitimately show up as orphaned) rather than a silently-ignored rule.
 */
public record IdentityUrn(IdentityType type, String name) {

    private static final String USER_PREFIX = "user:";
    private static final String GROUP_PREFIX = "group:";
    private static final String ROLE_PREFIX = "role:";

    /** {@code Optional.empty()} for wildcard sources ({@code *}/{@code ?}) — not checkable against a fixed identity list. */
    public static Optional<IdentityUrn> parse(String source) {
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
        return Optional.of(new IdentityUrn(IdentityType.ROLE, source));
    }

    private static boolean containsWildcard(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }
}
