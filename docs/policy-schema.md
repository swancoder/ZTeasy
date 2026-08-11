# ZTE YAML Policy Schema (ADR-011, ADR-012)

Defines allow/deny access rules for three relationship categories, enforced by
both the API gateway proxy and the MCP proxy. As of ADR-012, YAML is the
**sole** source of truth for all three categories — there is no DB fallback
anywhere (the `access_policies` table and `PolicyService` were retired):

| Category | Governs | Enforced by |
|---|---|---|
| `users2service` | Human user (Keycloak realm role) → gateway REST service | `ZteAuthorizationFilter` for Gateway-routed paths; `AdminAuthorizationFilter` for the local `/api/v1/admin/**` controller (see ADR-012 — `GlobalFilter`s don't run for non-routed requests). No match → deny. |
| `service2service` | Calling service/agent identity (JWT `azp`) → gateway REST service | `ServiceToServiceAuthorizationFilter`. No match → `zte.policy.default-effect`. |
| `agentMcpToolCalls` | MCP agent identity (JWT `azp`) → MCP tool name | `YamlMcpPolicyEngine`. No match → `zte.policy.default-effect`. |

Full worked example covering all three: [`docs/examples/zte-policies-example.yaml`](examples/zte-policies-example.yaml).
The file the running gateway actually loads by default: [`gateway-service/src/main/resources/zte-policies.yaml`](../gateway-service/src/main/resources/zte-policies.yaml).

## Top-level structure

```yaml
schemaVersion: 1        # required, must be exactly 1 today
users2service: [ ... ]  # list of rules, may be empty/omitted
service2service: [ ... ]
agentMcpToolCalls: [ ... ]
```

Unknown top-level keys are **rejected** (not silently ignored) — a typo in a
key name fails the load rather than being masked.

## Rule fields

Every rule, in every category, has the same shape:

| Field | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | string | Unique across the **whole document** (not just its category). Referenced in audit logs and validation errors. |
| `effect` | yes | `ALLOW` \| `DENY` | What the rule does when it matches. |
| `source` | yes | string (pattern) | Caller identity: a realm role name (`users2service`), a calling service/agent's OAuth2 client id (`service2service`, `agentMcpToolCalls`). `users2service` also accepts an IdP URN — `user:<name>`, `group:<name>`, `role:<name>` (ADR-014); a bare name is still exactly equivalent to `role:<name>`. |
| `target` | yes | string (pattern) | What's being accessed: a service name (`users2service`, `service2service`) or an MCP tool name (`agentMcpToolCalls`). |
| `pathPattern` | no | string (Ant pattern) | Request path scope. Omit/`null` to match any path. **Unused by `agentMcpToolCalls`** (tool calls aren't path-scoped). |
| `methods` | no | string | Comma-separated HTTP verbs, or `"*"`. Omit/`null` to match any method. **Unused by `agentMcpToolCalls`**. |
| `priority` | no | integer | Tie-breaker *within the same effect* when multiple rules match (higher wins). Default `0`. Never breaks an ALLOW vs DENY tie — see Precedence below. |

`source`/`target`/`pathPattern` are matched with Spring's `AntPathMatcher`
(via `PolicyMatcher`, the single shared evaluation engine for all three
categories): `"*"` matches anything, `"agent-*"` matches a prefix, an exact
string matches itself.

## URN sources for `users2service` (ADR-014)

`source` in a `users2service` rule additionally accepts an IdP-identity URN,
parsed by `IdentityUrn.parse`:

| Form | Matches |
|---|---|
| `role:<name>` | A Keycloak realm role — identical to the bare `<name>` form below. |
| `user:<name>` | A Keycloak user, by `preferred_username`. |
| `group:<name>` | A Keycloak group, by name (requires the `groups` JWT claim — see `zte-gateway`'s `groups-mapper` protocol mapper). |
| `<name>` (no prefix) | Backward-compatible bare role name — treated identically to `role:<name>`. |
| An unrecognized prefix (e.g. `agent:foo`) | Treated as a literal role name (`role:"agent:foo"`), not silently ignored — a mistyped prefix still produces a checkable (and likely orphaned) rule rather than a rule that never matches anything. |
| A pattern containing `*`/`?` | Not resolvable to a URN — skipped by orphaned-rule checking, but still matched by `PolicyMatcher`'s normal `AntPathMatcher` semantics as before. |

Every 15 minutes (`zte.idp.sync-interval-ms`, default `900000`) — or on demand
via `POST /api/v1/admin/identities/sync` — the gateway syncs Keycloak's
users/groups/roles into a local `idp_identities` Postgres cache. A
`users2service` rule whose `source` doesn't resolve to any cached identity is
never rejected, but logs an SLF4J `WARN` ("ORPHANED RULE: ...") at load time
and on every reload, and is flagged in the Admin Console's Policies tab. See
[ADR-014](adr/ADR-014-idp-identity-sync.md).

## Precedence

For a given request, every rule in the relevant category whose `source`,
`target`, `pathPattern`, and `methods` all match is a candidate:

1. If **any** candidate has `effect: DENY`, the request is denied — using the
   highest-`priority` DENY candidate as the "matched rule" in logs. This holds
   **regardless of declaration order or any candidate's priority relative to
   ALLOW candidates** — deny always wins.
2. Otherwise, if any candidate has `effect: ALLOW`, the request is allowed —
   using the highest-`priority` ALLOW candidate.
3. Otherwise (no candidate at all): deny. `users2service` denies directly
   (as of ADR-012); `service2service`/`agentMcpToolCalls` resolve to
   `zte.policy.default-effect` (`application.yml`, default `DENY`).

## Validation

Runs once at load (startup, and again on every `POST
/api/v1/internal/policies/reload`) via `PolicyValidator`. All violations are
collected in one pass, not just the first:

**Errors (load-blocking — the whole document is rejected, fail-closed):**
- Unsupported/missing `schemaVersion`.
- Any rule missing `id`, `effect`, `source`, or `target`.
- An invalid `methods` value (not `"*"` and not a comma-separated list of `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`).
- A duplicate `id` anywhere in the document.
- An exact duplicate rule (same category, source, target, pathPattern, methods, **and** effect).

**Warnings (non-blocking — logged, document still loads):**
- Two rules in the same category with the same source/target/pathPattern/methods but **different** effects. Not an error because deny-overrides-allow resolves the conflict deterministically at evaluation time — but worth an operator's attention, since it usually means one of the two rules is dead weight.

On any load-blocking error at **startup**, the gateway fails to start
(`ApplicationContext` refresh fails) rather than serving traffic with an
empty/stale policy set. On any load-blocking error during a **reload**, the
previously active document remains in effect and the reload endpoint reports
the errors — never a partial application.

## Runtime reload

```
POST /api/v1/internal/policies/reload   # unauthenticated, network-perimeter (zt-agents, ops scripts)
POST /api/v1/admin/policies/reload      # ADMIN-JWT-gated (ADR-012, the Admin Console SPA)
```

Both re-read and re-validate the configured file (`zte.policy.file`),
atomically swapping the active document on success, and render the same
response shape. In-flight requests that already read the previous document
complete against it — no torn reads, no dropped connections. See ADR-011 for
why this is an explicit endpoint rather than an automatic filesystem watch,
and ADR-012 for why there are two endpoints rather than one.

## Configuration (`application.yml`)

```yaml
zte:
  policy:
    file: classpath:zte-policies.yaml   # or file:/path/to/policies.yaml
    default-effect: DENY                # ALLOW | DENY — applies to service2service and agentMcpToolCalls NO_MATCH
    user-client-id: zte-gateway         # the interactive-user OAuth2 client; anything else is a service principal
```
