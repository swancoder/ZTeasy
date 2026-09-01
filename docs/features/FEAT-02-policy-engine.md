# FEAT-02 — YAML Policy Engine

**Maturity:** Production-ready
**Depends on:** FEAT-08 (identities, for URN sources and orphan detection)
**Feeds:** FEAT-01, FEAT-04, FEAT-11, FEAT-13
**Detail:** [SPECS §5.3](../SPECS.md), [policy-schema.md](../policy-schema.md) · [ADR-011](../adr/ADR-011-yaml-policy-engine.md), [ADR-012](../adr/ADR-012-full-yaml-migration-and-admin-console.md), [ADR-023](../adr/ADR-023-policy-rule-mcp-target.md)

## What it does

One YAML document is the single source of truth for every access decision in
the system, across four relationship categories:

| Category | Answers |
|---|---|
| `users2service` | may this *person* reach this service or console API? |
| `service2service` | may this *service* call that service? |
| `agentMcpToolCalls` | may this *agent* invoke this tool? |
| `agentMcpToolHolds` | must this tool call go to a human even when allowed? |

Rules share one shape (id, effect, source, target, path, methods, priority,
optional MCP backend scope). The file is validated on load, hot-swappable at
runtime, and every decision is traceable to the rule id that produced it.

## Why it matters

Policy that lives in code is policy nobody outside engineering can read or
change. Here it is one reviewable artefact: an auditor can read what is
allowed, an operator can change it without a deployment, and every decision
in the audit trail names the rule that made it. Deny-by-default means adding
a service does not accidentally expose it.

## Behaviour

**Given** a request matching no rule, **when** it is evaluated, **then** it is
refused — absence of a rule is a refusal, not a gap.

**Given** rules that both allow and deny the same request, **when** they are
evaluated, **then** deny wins regardless of priority or declaration order.

**Given** a rule whose `source` is an identity URN (`role:`, `user:`,
`group:`, `client:`), **when** the caller's enriched identity list is built,
**then** it matches identically to the bare form — the prefix is precision,
not a different mechanism.

**Given** an edited policy file, **when** a reload is triggered, **then** the
new document is validated first; on any error (bad schema, duplicate id) the
**previous document stays active** and the response lists the errors. A
reload never leaves the gate without policy.

**Given** a rule scoped to a specific MCP backend, **when** the gateway is
repointed at a different backend, **then** that rule stops applying rather
than silently matching a same-named tool elsewhere.

**Given** a rule naming an identity that does not exist in the IdP, **when**
policy is loaded, **then** it is flagged as orphaned in logs and in the Admin
Console — but never auto-deleted, because a cold identity cache must not
delete an operator's rules.

**Given** a rule an operator switched off (stage 31's activation overlay),
**when** a matching request is evaluated, **then** the rule contributes
nothing to the decision, and the would-have-matched hit is logged as
`POLICY_INACTIVE_MATCH` (and annotated on MCP audit rows) — the outcome
changes, the record of why never disappears. Switching a DENY rule off
requires an explicit confirmation in the console.

## Limits

- Evaluation is a linear scan per category per request — correct and fast at
  the current rule count, not designed for thousands of rules.
- No attribute conditions yet (ABAC, e.g. "only during business hours");
  argument-level restrictions live in FEAT-05 instead.
- `zte.policy.default-effect` is global, not per-category.
