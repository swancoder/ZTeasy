# ZTE YAML Policy Schema (ADR-011, ADR-012)

Defines allow/deny access rules for four relationship categories, enforced by
both the API gateway proxy and the MCP proxy. As of ADR-012, YAML is the
**sole** source of truth for all four categories — there is no DB fallback
anywhere (the `access_policies` table and `PolicyService` were retired):

| Category | Governs | Enforced by |
|---|---|---|
| `users2service` | Human user (Keycloak realm role) → gateway REST service | `ZteAuthorizationFilter` for Gateway-routed paths; `AdminAuthorizationFilter` for the local `/api/v1/admin/**` controller (see ADR-012 — `GlobalFilter`s don't run for non-routed requests). No match → deny. |
| `service2service` | Calling service/agent identity (JWT `azp`) → gateway REST service | `ServiceToServiceAuthorizationFilter`. No match → `zte.policy.default-effect`. |
| `agentMcpToolCalls` | MCP agent identity (JWT `azp`) → MCP tool name | `YamlMcpPolicyEngine`. No match → `zte.policy.default-effect`. |
| `agentMcpToolHolds` (ADR-019) | MCP tool calls routed to a human for approval even when `agentMcpToolCalls` would ALLOW them | `YamlMcpPolicyEngine`, via `PolicyMatcher.matchAny` — a separate match, not part of `evaluate`'s ALLOW/DENY precedence (see below). No match → the plain ALLOW/DENY from `agentMcpToolCalls` stands, unmodified. |

Full worked example covering all four: [`docs/examples/zte-policies-example.yaml`](examples/zte-policies-example.yaml).
The file the running gateway actually loads by default: [`gateway-service/src/main/resources/zte-policies.yaml`](../gateway-service/src/main/resources/zte-policies.yaml).

## Top-level structure

```yaml
schemaVersion: 1        # required, must be exactly 1 today
users2service: [ ... ]  # list of rules, may be empty/omitted
service2service: [ ... ]
agentMcpToolCalls: [ ... ]
agentMcpToolHolds: [ ... ]   # ADR-019 — see "Hold rules" below
```

Unknown top-level keys are **rejected** (not silently ignored) — a typo in a
key name fails the load rather than being masked.

## Rule fields

Every rule, in every category, has the same shape:

| Field | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | string | Unique across the **whole document** (not just its category). Referenced in audit logs and validation errors. |
| `effect` | yes | `ALLOW` \| `DENY` | What the rule does when it matches. |
| `source` | yes | string (pattern) | Caller identity: a realm role name (`users2service`), a calling service/agent's OAuth2 client id (`service2service`, `agentMcpToolCalls`). Every category also accepts an IdP URN — `user:<name>`, `group:<name>`, `role:<name>` (ADR-014), `client:<clientId>` (ADR-015); a bare name is still exactly equivalent to the category's implied prefix (`role:` for `users2service`, `client:` for `service2service`/`agentMcpToolCalls`). |
| `target` | yes | string (pattern) | What's being accessed: a service name (`users2service`, `service2service`) or an MCP tool name (`agentMcpToolCalls`). |
| `pathPattern` | no | string (Ant pattern) | Request path scope. Omit/`null` to match any path. **Unused by `agentMcpToolCalls`** (tool calls aren't path-scoped). |
| `methods` | no | string | Comma-separated HTTP verbs, or `"*"`. Omit/`null` to match any method. **Unused by `agentMcpToolCalls`**. |
| `priority` | no | integer | Tie-breaker *within the same effect* when multiple rules match (higher wins). Default `0`. Never breaks an ALLOW vs DENY tie — see Precedence below. |
| `mcpTarget` | no | string (exact match, not a pattern) | Which MCP backend this rule applies to, matched against the configured `mcp-backend.name` (ADR-023). Omit/`null` to match any backend. **Unused by `users2service`/`service2service`.** |

`source`/`target`/`pathPattern` are matched with Spring's `AntPathMatcher`
(via `PolicyMatcher`, the single shared evaluation engine for all three
categories): `"*"` matches anything, `"agent-*"` matches a prefix, an exact
string matches itself.

## URN sources (ADR-014, extended by ADR-015)

`source` in any category additionally accepts an IdP-identity URN, parsed by
`IdentityUrn.parse(source, defaultType)` — `defaultType` is what a
*bare* (no-prefix) source implies, and depends on the category:

| Form | Matches |
|---|---|
| `role:<name>` | A Keycloak realm role. |
| `user:<name>` | A Keycloak user, by `preferred_username`. |
| `group:<name>` | A Keycloak group, by name (requires the `groups` JWT claim — see `zte-gateway`'s `groups-mapper` protocol mapper). |
| `client:<clientId>` | An OIDC client (ADR-015) — matches the caller's JWT `azp` claim, by Keycloak `clientId`. |
| `<name>` (no prefix) | `users2service`: identical to `role:<name>`. `service2service`/`agentMcpToolCalls`: identical to `client:<name>` — every rule in those two categories predates ADR-015 and was already a bare client id. |
| An unrecognized prefix (e.g. `agent:foo`) | Treated as a literal name of the category's default type, not silently ignored — a mistyped prefix still produces a checkable (and likely orphaned) rule rather than a rule that never matches anything. |
| A pattern containing `*`/`?` | Not resolvable to a URN — skipped by orphaned-rule checking, but still matched by `PolicyMatcher`'s normal `AntPathMatcher` semantics as before. |

Every 15 minutes (`zte.idp.sync-interval-ms`, default `900000`) — or on demand
via `POST /api/v1/admin/identities/sync` — the gateway syncs Keycloak's
users/groups/roles/**clients** into a local `idp_identities` Postgres cache
(clients as of ADR-015 — fetches *every* client in the realm, not just
`serviceAccountsEnabled` ones, an accepted MVP simplification). A rule in any
category whose `source` doesn't resolve to any cached identity is never
rejected, but logs an SLF4J `WARN` ("ORPHANED RULE: ...") at load time and
on every reload, and is flagged in the Admin Console's Policies tab. See
[ADR-014](adr/ADR-014-idp-identity-sync.md), [ADR-015](adr/ADR-015-machine-identities-and-urn-unification.md).

## Precedence

For a given request, every rule in the relevant category whose `source`,
`target`, `pathPattern`, `methods`, and `mcpTarget` (ADR-023 — `agentMcpToolCalls`/
`agentMcpToolHolds` only, checked against the configured `mcp-backend.name`)
all match is a candidate:

1. If **any** candidate has `effect: DENY`, the request is denied — using the
   highest-`priority` DENY candidate as the "matched rule" in logs. This holds
   **regardless of declaration order or any candidate's priority relative to
   ALLOW candidates** — deny always wins.
2. Otherwise, if any candidate has `effect: ALLOW`, the request is allowed —
   using the highest-`priority` ALLOW candidate.
3. Otherwise (no candidate at all): deny. `users2service` denies directly
   (as of ADR-012); `service2service`/`agentMcpToolCalls` resolve to
   `zte.policy.default-effect` (`application.yml`, default `DENY`).

## Hold rules — `agentMcpToolHolds` (ADR-019)

A separate list from `agentMcpToolCalls`, reusing `PolicyRule`'s shape purely
for authoring convenience — `effect` is unused (write `ALLOW` by convention)
and `pathPattern`/`methods` are unused, same as `agentMcpToolCalls`. Matched
via `PolicyMatcher.matchAny(rules, sources, target)`: the highest-priority
rule (any effect) whose `source`/`target` match, independent of ALLOW/DENY
precedence entirely.

`YamlMcpPolicyEngine` checks it only *after* `agentMcpToolCalls` has already
resolved to ALLOW (explicitly, or via `NO_MATCH` + `default-effect: ALLOW`):
a match downgrades the decision to **HOLD** — the call is neither forwarded
nor denied, but parked in `pending_approvals` for a human to approve or
reject (`POST /api/v1/admin/approvals/{id}/approve|reject`). A `DENY` from
`agentMcpToolCalls` is always final — a hold rule can only tighten an
otherwise-allowed call, never loosen a denied one.

```yaml
agentMcpToolCalls:
  - id: mcp-allow-crm-send-email
    effect: ALLOW
    source: "client:crm-account-health-emea-01"
    target: send_email

agentMcpToolHolds:
  - id: mcp-hold-crm-send-email
    effect: ALLOW              # unused, kept ALLOW by convention
    source: "client:crm-account-health-emea-01"
    target: send_email
```

Why a separate list rather than a third `RuleEffect` value shared by every
category: `users2service`/`service2service`'s authorization filters switch
exhaustively over `PolicyEvaluation.Outcome`, and neither has any meaningful
notion of holding a REST call for a human — see ADR-019's Decision section
for the full reasoning.

## ACAP scope profiles (Stage 3/ADR-020; agent metadata & thresholds Stage 6/ADR-022)

A separate, additive, opt-in per-agent enrichment layer — not part of
`zte-policies.yaml` at all. An agent with no ACAP profile is governed purely
by `agentMcpToolCalls`/`agentMcpToolHolds` above, exactly as before this
stage. An agent *with* a profile gets a further, argument-aware tightening
pass on top of whatever those two categories already decided: it can only
turn an ALLOW/HOLD into a DENY, never the reverse.

**Why this instead of extending `PolicyRule`:** the demo's core technical
thesis is that the *same* tool name must be allowed or denied differently
depending on its arguments (territory, requested fields) — something
`PolicyRule`'s Ant-pattern source/target matching has no way to express.
Rather than bolting argument-matching onto the generic rule engine shared by
`users2service`/`service2service`/`agentMcpToolCalls` (which would force
every category to carry a concept only one of them needs), this is its own
small, purpose-built model and evaluator, consulted only for agents that opt
in.

**File format** — one YAML file per agent under `zte.acap.profiles-location`
(default `classpath:acap-profiles/*.yaml`; filename is not significant,
`agentId` inside the document is what's matched):

```yaml
agentId: crm-account-health-emea-01
territory: EMEA

# Stage 6, ADR-022 — display-only, no enforcement:
agent:
  name: Account-Health Assistant
  client: Nordwind Components
  owner:
    name: Sales Operations Lead
    email: sales-ops@nordwind.example
  deploymentDate: "2026-08-01"
  reauthDue: "2026-02-01"
risk:
  euAiActClass: limited
  internalTier: 2

scope:
  read:
    - resource: contacts
      fields: [name, company, lifecycle_stage, last_activity, deal_ids]
    - resource: deals
      fields: [name, stage, amount, close_date, risk_flag]
    - resource: activities
      fields: []          # empty = no field restriction on this resource
  writeAllowed: false

# Stage 6, ADR-022 — enforced (see "Checks" below):
thresholds:
  - metric: followup_drafts_per_day
    toolName: draft_followup   # ZTeasy addition, not part of the source ACAP JSON — see below
    limit: 30
    onExceed: hold              # the only value implemented
```

A deliberately simplified subset of the source ACAP schema (see
`examples-from-vlad/acap-crm-account-health.json`) — no `platforms`/
`hold`/`never`/`audit`/`evidence`/`deny_response`:

| ACAP concept | Where it lives in ZTeasy |
|---|---|
| `agent.name`/`agent.client`/`agent.owner`/`agent.deployment_date`/`agent.reauth_due` | `agent.{name,client,owner,deploymentDate,reauthDue}` — display-only (Admin Console Governance tab), no enforcement |
| `assigned.territory` | `territory` (single territory per agent; ACAP's per-resource `filter.territory` isn't modeled — this demo's agent has one uniform territory) |
| `risk.eu_ai_act_class`/`risk.internal_tier` | `risk.{euAiActClass,internalTier}` — display-only, no enforcement |
| `scope.read.allow[].{resource,fields}` | `scope.read[].{resource,fields}` |
| `scope.read.deny` | Not modeled — derivable from the allow-list plus the territory check, so storing it separately would just be redundant data that could drift |
| `scope.write.allow`/`scope.write.deny` | `scope.writeAllowed` (a single flag — this demo agent never has partial write grants; extend to a list if a future profile needs one) |
| `thresholds[].{metric,limit,on_exceed}` | `thresholds[].{metric,limit,onExceed}` — enforced (see "Checks" below); `toolName` is a ZTeasy addition (see below) |
| `hold[]` | Not here — ADR-019's `agentMcpToolHolds`, already handles this independently |
| `never[]` | Not loaded data — realized as the fixed reason labels each check below already produces (`bulk_export_contacts`, `change_record`, `read_outside_territory`, `fields.deny`) |
| `platforms`, `audit`, `evidence`, `deny_response` | No consumer in this codebase — not modeled |

**Why `thresholds[].toolName` isn't in the source ACAP JSON:** a metric name
like `followup_drafts_per_day` doesn't mechanically derive the tool it
counts (`draft_followup`) by any naming convention robust enough to trust
for an arbitrary future metric — rather than guess, the tool name is
explicit.

**Tool-name-to-resource convention** (matches the demo script's own tool
names exactly): `read_<resource>` (e.g. `read_contacts` → resource
`contacts`), `update_*` (write-shaped), `export_*` (bulk-shaped). Any other
tool name (e.g. `send_email`, `draft_followup`) — no opinion, deferred
entirely to `agentMcpToolCalls`/`agentMcpToolHolds`.

**Checks** (`AcapScopeEvaluator`, run only when the coarse decision isn't
already DENY):
1. `export_*` tool → always DENY (`bulk_export_contacts`) — bypasses
   territory/field scoping by definition, regardless of any grant.
2. `update_*` tool, `writeAllowed: false` → DENY (`change_record`).
3. `read_<resource>` tool with no matching `scope.read[]` entry → DENY (no grant for that resource).
4. `read_<resource>` tool, `arguments.territory` ≠ `territory` (including
   when the argument is simply missing) → DENY (`read_outside_territory`).
5. `read_<resource>` tool, `arguments.fields` contains anything outside that
   resource's `fields` list (when the list is non-empty) → DENY (`fields.deny`).
   Only enforced when `fields` is explicitly present in the call — this
   layer has no opinion on what a tool implementation defaults to returning
   when `fields` is omitted entirely.

Anything that doesn't match one of the above leaves the coarse
`agentMcpToolCalls`/`agentMcpToolHolds` decision untouched.

**Thresholds** (`AcapScopeEvaluator.checkThresholds`, Stage 6/ADR-022 — a
separate pass, run only when the checks above didn't already DENY): for
every `thresholds[]` entry whose `toolName` matches this call,
`AcapThresholdTracker` (in-memory, per-agent-per-metric, resets daily)
increments the counter regardless of the current decision; if the new count
exceeds `limit`, `onExceed: hold`, and the decision was a plain ALLOW, it's
escalated to HOLD. Never touches an existing HOLD/DENY, never invents an
ALLOW — the one-directional contract mirrors the scope checks above, just
tightening toward HOLD instead of DENY.

**Loading** — best-effort, unlike the fail-fast `zte.policy.file`: a
location matching zero files is normal (ACAP profiles are opt-in), and a
malformed or duplicate individual file is logged and skipped rather than
failing gateway startup — that one agent just falls back to coarse-only
enforcement, which is still a safe (default-deny) posture. Reload via `POST
/api/v1/admin/acap-profiles/reload`; `GET` lists what's currently loaded,
each entry paired with its current threshold usage (`AcapProfileView`,
Stage 6) — the Admin Console's Governance tab's "ACAP Profiles" section. No
unauthenticated `/api/v1/internal/**` counterpart yet (no `zt-agents`/ops
consumer today).

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
  acap:
    profiles-location: classpath:acap-profiles/*.yaml   # Stage 3, ADR-020 — zero matches is fine, not an error
```
