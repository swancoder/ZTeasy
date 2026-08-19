# ADR-023: `mcpTarget` — Scoping `agentMcpToolCalls`/`agentMcpToolHolds` Rules to a Specific MCP Backend

## Status
Accepted

## Context

Raised directly by the user reviewing the ACAP/DIGI-KAI governance-demo work
(Stages 1–6, ADR-019 through ADR-022): `agentMcpToolCalls`/`agentMcpToolHolds`
rules match a tool call purely by `target` (the tool name) — there is no
notion of *which* MCP backend a rule's tool name belongs to. Today that's
harmless in practice, since `McpBackendClient` only ever forwards to one
configured backend (`mcp-backend.uri`/`mcp-backend.name`) — there's nothing
for a rule to be ambiguous *against*. But the gap is real: if the gateway is
ever repointed at a different MCP backend (or, longer-term, fronts more than
one), a rule authored against one backend's tool semantics (e.g. `read_contacts`
meaning "HubSpot contacts, scoped by the `territory` property") could silently
keep matching a same-named tool on a completely different backend with
different semantics — an unnoticed policy mismatch, not a loud failure.

## Decision

### A rule field, not new routing infrastructure

Two shapes were on the table (put to the user directly, see the conversation
this ADR is attached to): (1) add `mcpTarget` to `PolicyRule`, matched
against the existing `mcp-backend.name` config value; (2) build real
multi-backend MCP routing first (registry-driven, mirroring
`InventoryRouteDefinitionLocator`'s REST story), then extend policy rules to
disambiguate between concurrently-routed backends. Chose (1): there is
still exactly one configured MCP backend today — building routing
infrastructure for backends that don't exist yet would be speculative
generality. (1) closes the *actual* risk articulated above (a rule silently
misapplying after the single backend is swapped) without that added scope,
and composes cleanly if/when (2) is ever built — `mcpTarget` is exactly the
field a future registry-driven router would also need to disambiguate rules
by.

### Matched against `mcp-backend.name`, exact string match

`PolicyRule` gains `mcpTarget` (nullable, backward-compatible via a
convenience constructor — every pre-ADR-023 call site across 8 files keeps
compiling unchanged). `PolicyMatcher` gains an `mcpIdentifier` parameter on
both `evaluate` and `matchAny`, via new overloads — the existing 5-arg
`evaluate`/3-arg `matchAny` delegate with `mcpIdentifier = null`, so the
three REST authorization filters (`ZteAuthorizationFilter`,
`ServiceToServiceAuthorizationFilter`, `AdminAuthorizationFilter`) don't
need to change at all. `YamlMcpPolicyEngine` is the only caller of the new
overloads, injecting the configured `mcp-backend.name` (the same value
`LoggingMcpAuditService` already uses for `request_logs.target_service` —
reused, not duplicated).

Matching is an exact string equality, not an Ant pattern like `source`/
`target` — backend identifiers are flat config values (`mcp-backend.name`),
not hierarchical paths, so pattern matching would add expressiveness
nothing needs.

### Unscoped means universal — same convention as `pathPattern`/`methods`

A rule with no `mcpTarget` matches regardless of backend — the same
"absent means unconstrained" convention `pathPattern`/`methods` already
established for the reverse case (used by REST categories, unused by MCP).
This keeps every pre-ADR-023 rule (in any deployment of this gateway, not
just this repo's own `zte-policies.yaml`) working unchanged with no
migration required.

**Consequence, deliberately accepted:** a rule that *does* specify
`mcpTarget` but is evaluated via the 5-arg `evaluate`/3-arg `matchAny`
(`mcpIdentifier = null`, i.e. a REST call) fails to match — mirroring how a
`pathPattern`-scoped rule already fails to match an MCP call (`path = null`).
In practice this only matters if an operator mistakenly sets `mcpTarget` on
a `users2service`/`service2service` rule, which is documented as
MCP-category-only.

### This repo's own `zte-policies.yaml`: scoped grants, unscoped safety nets

Every per-agent `ALLOW` grant and both `agentMcpToolHolds` entries now carry
`mcpTarget: hubspot-mcp` — real tools with real backend-specific semantics,
which should stop applying if the backend is ever swapped. The three
name-shape `DENY` safety nets (`delete*`, `drop*`, `export_all_data`)
deliberately stay unscoped — a "these tool-name shapes are always dangerous"
protection you want to keep regardless of which backend is configured, not
one you'd want to silently lose on a backend swap.

### `never`, realized as a semantic label, doesn't need this

`AcapScopeEvaluator`'s tool-name-prefix conventions (`read_*`/`update_*`/
`export_*`, ADR-020) and `AcapProfile` are unaffected by this ADR — out of
scope for this change (the user's concern was specifically about
`agentMcpToolCalls`/`agentMcpToolHolds` rules). An ACAP profile is already
implicitly single-backend per agent in this codebase's current design;
extending it with the same `mcpTarget` concept, if a future stage needs it,
is a natural follow-up but not built here.

### `PolicyValidator`'s duplicate/conflict detection updated too

Found while implementing, not part of the original ask: the duplicate-rule
tuple key (`source|target|pathPattern|methods`) didn't include `mcpTarget`
— two rules differing only by backend (e.g. the same source/target ALLOWed
on one backend, DENYed on another — a legitimate, intentional pair) would
have been flagged as a false duplicate/conflict. Fixed by adding `mcpTarget`
to the tuple key, with a regression test for both the false-positive case
and the still-correctly-flagged true-duplicate case.

## Self-Criticism

- **No live second backend to prove this against.** Every test exercises
  the mechanism directly (`PolicyMatcher`/`YamlMcpPolicyEngine` unit tests
  passing a second, fake `mcpIdentifier`), not an actual second MCP server —
  this is schema/matching-logic hardening for a scenario this repo doesn't
  yet have, not a bug fix for one it does.
- **`AcapProfile` doesn't get the same treatment.** An ACAP profile has the
  identical latent risk (its `scope.read[].resource`/tool-prefix conventions
  are just as backend-specific as a `PolicyRule.target`), just not raised in
  this conversation — flagged here rather than silently left inconsistent.
- **Exact-match only, no Ant pattern.** Fine for one backend identifier
  value; if `mcp-backend.name` ever becomes a *set* of concurrently-active
  backends, this equality check (and the whole "one configured backend"
  assumption `YamlMcpPolicyEngine` makes) needs revisiting together.

## Consequences

- Zero behavior change for any rule that doesn't specify `mcpTarget` —
  every existing test, every existing deployment's policy file, keeps
  working unchanged.
- This repo's own `zte-policies.yaml`/`docs/examples/zte-policies-example.yaml`
  now demonstrate the field for real, not just document it.
- The field is exactly what a future registry-driven multi-backend MCP
  router (if ever built) would need to disambiguate rules by — this ADR's
  scoped fix composes with that future work rather than needing to be redone.
