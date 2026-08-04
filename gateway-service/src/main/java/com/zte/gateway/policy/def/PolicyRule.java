package com.zte.gateway.policy.def;

/**
 * A single allow/deny rule, shared by all three {@link PolicyDocument} categories
 * (users2service, service2service, agentMcpToolCalls) rather than three separate
 * rule types — the shape (source identity → target, optionally scoped by path and
 * method, with an effect and a tie-break priority) is identical across all three;
 * a class hierarchy would only add ceremony. {@code pathPattern}/{@code methods}
 * are simply unused (left {@code null}) by agentMcpToolCalls rules, whose "target"
 * is a tool name rather than a service.
 *
 * <p>{@code source} and {@code target} are Ant-style patterns (matched with
 * {@link org.springframework.util.AntPathMatcher}, the same matcher
 * {@code PolicyService} already uses) — {@code "*"} matches anything,
 * {@code "agent-*"} matches a prefix, or an exact string matches itself.
 *
 * @param id          unique within the whole {@link PolicyDocument} (validated by {@link PolicyValidator})
 * @param effect      ALLOW or DENY
 * @param source      pattern matched against the caller identity (role name, service client id, or agent id)
 * @param target      pattern matched against the target resource (service name or MCP tool name)
 * @param pathPattern Ant-style request path pattern; {@code null}/absent matches any path (users2service/service2service only)
 * @param methods     comma-separated HTTP verbs, or {@code "*"}; {@code null}/absent matches any method (users2service/service2service only)
 * @param priority    higher priority is preferred when multiple rules of the same effect match; ties broken by declaration order
 */
public record PolicyRule(
        String id,
        RuleEffect effect,
        String source,
        String target,
        String pathPattern,
        String methods,
        int priority
) {}
