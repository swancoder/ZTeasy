package com.zte.gateway.policy.def;

/**
 * A single allow/deny rule, shared by all three {@link PolicyDocument} categories
 * (users2service, service2service, agentMcpToolCalls) rather than three separate
 * rule types — the shape (source identity → target, optionally scoped by path and
 * method, with an effect and a tie-break priority) is identical across all three;
 * a class hierarchy would only add ceremony. {@code pathPattern}/{@code methods}
 * are simply unused (left {@code null}) by agentMcpToolCalls rules, whose "target"
 * is a tool name rather than a service; conversely {@code mcpTarget} is simply
 * unused by users2service/service2service rules.
 *
 * <p>{@code source} and {@code target} are Ant-style patterns, matched via
 * {@link org.springframework.util.AntPathMatcher} by {@link PolicyMatcher} —
 * {@code "*"} matches anything, {@code "agent-*"} matches a prefix, or an exact
 * string matches itself.
 *
 * @param id          unique within the whole {@link PolicyDocument} (validated by {@link PolicyValidator})
 * @param effect      ALLOW or DENY
 * @param source      pattern matched against the caller identity (role name, service client id, or agent id)
 * @param target      pattern matched against the target resource (service name or MCP tool name)
 * @param pathPattern Ant-style request path pattern; {@code null}/absent matches any path (users2service/service2service only)
 * @param methods     comma-separated HTTP verbs, or {@code "*"}; {@code null}/absent matches any method (users2service/service2service only)
 * @param priority    higher priority is preferred when multiple rules of the same effect match; ties broken by declaration order
 * @param routeTo     who may decide a call this rule holds — a URN in the same vocabulary the {@code source} field uses ({@code role:APPROVER}, {@code user:jane}); {@code null}/absent keeps ADR-026's behaviour, where any interactive user may decide (agentMcpToolHolds only — ADR-034). A routed item stays visible to everyone: the queue is evidence that the system held something, and hiding it would defeat that.
 * @param mcpTarget   which MCP backend this rule applies to, matched against the configured {@code mcp-backend.name} (agentMcpToolCalls/agentMcpToolHolds only — ADR-023); {@code null}/absent matches regardless of backend, same "unscoped means universal" convention {@code pathPattern}/{@code methods} already use. Exists so a rule authored against one MCP backend's tool semantics can't silently keep matching if the gateway is later repointed at a different backend exposing a same-named but semantically different tool — see ADR-023's Decision section for why this is a rule field rather than routing infrastructure.
 */
public record PolicyRule(
        String id,
        RuleEffect effect,
        String source,
        String target,
        String pathPattern,
        String methods,
        int priority,
        String mcpTarget,
        String routeTo
) {

    /** Convenience constructor for every call site written before ADR-023's {@code mcpTarget} field existed. */
    public PolicyRule(String id, RuleEffect effect, String source, String target, String pathPattern,
                       String methods, int priority) {
        this(id, effect, source, target, pathPattern, methods, priority, null, null);
    }

    /** Convenience constructor for every call site written before ADR-034's {@code routeTo} field existed. */
    public PolicyRule(String id, RuleEffect effect, String source, String target, String pathPattern,
                       String methods, int priority, String mcpTarget) {
        this(id, effect, source, target, pathPattern, methods, priority, mcpTarget, null);
    }
}
