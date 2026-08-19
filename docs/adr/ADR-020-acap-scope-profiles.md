# ADR-020: ACAP Scope Profiles — Argument/Field-Level Policy Tightening

## Status
Accepted

## Context

Stage 3 of the ACAP/DIGI-KAI governance-demo plan (see `examples-from-vlad/`,
ADR-019's Context for the full background): the demo owner's priority #3 —
"the gate must look at arguments, not just the tool name." The coarse
`agentMcpToolCalls`/`agentMcpToolHolds` rules (ADR-011/ADR-019) match on
tool name alone; `read_contacts(territory=EMEA)` and
`read_contacts(territory=NA)` are indistinguishable to them. The demo script's
own worked example requires exactly this distinction, plus a data-minimization
field check (`read_contacts(fields=[id_number])` denied even for the right
territory) and a read-only agent's write attempts denied regardless of any
coarse grant.

## Decision

### A separate, additive model — not an extension of `PolicyRule`

Rejected: adding argument-matching fields directly to `PolicyRule`/shared
across `users2service`/`service2service`/`agentMcpToolCalls`. Those two REST
categories have no concept of "territory" or "requested fields" — forcing
the shared rule shape to carry fields only one category ever uses would be
the same mistake ADR-019 avoided for `HOLD`. Instead: a new, small,
independently-loaded model (`AcapProfile`/`AcapScope`/`AcapReadGrant`,
`gateway-service/.../mcp/acap/`) and evaluator (`AcapScopeEvaluator`),
consulted from `YamlMcpPolicyEngine.evaluate()` only for agents that have
opted in with a profile file. An agent with none is governed exactly as
before this stage — full backward compatibility, zero risk to `agent-a`/
`agent-b`'s existing behavior.

### Tightens only, never loosens

`YamlMcpPolicyEngine.evaluate()`'s existing coarse pass (ALLOW/DENY/HOLD,
ADR-011/ADR-019) runs first, completely unchanged. If it resolves to DENY,
that's final — `AcapScopeEvaluator` isn't even consulted (confirmed by a
dedicated test, `deniedByCoarseRule_neverConsultsAcapProfile`). Otherwise,
`AcapProfileStore.find(agentId)` is checked; if a profile exists,
`AcapScopeEvaluator.tighten(...)` may downgrade ALLOW/HOLD to DENY, but can
never invent an ALLOW of its own or override an existing DENY. This mirrors
ADR-019's HOLD design exactly (a second, independent pass that can only
narrow the outcome) and keeps the coarse layer's own well-tested precedence
(`PolicyMatcher`, deny-always-wins) completely untouched.

### Deliberately simplified subset of the source ACAP schema

`AcapProfile` models `agentId`/`territory`/`scope.read[].{resource,fields}`/
`scope.writeAllowed` — not the full ACAP document. See
`docs/policy-schema.md`'s "ACAP scope profiles" section for the field-by-field
mapping and rationale for each omission (`scope.read.deny` is derivable, not
stored; `scope.write` collapses to one boolean since this demo agent has no
partial write grants; `hold`/`never`/`thresholds`/`risk`/`platforms`/`audit`/
`evidence`/`deny_response` are out of scope — `hold` is already ADR-019's
job, `never` is realized as fixed reason labels rather than loaded data, the
rest deferred to Stage 6).

### Tool-name-to-resource convention, not a lookup table

`read_<resource>`, `update_*`, `export_*` — string-prefix conventions
matching the demo script's own tool names exactly (`AcapScopeEvaluator`'s
Javadoc flags this as a convention that would need to become an explicit
mapping for a second, differently-named demo). Chosen over a configurable
mapping because building one now, for a single demo's fixed tool set, would
be speculative generality nothing has asked for yet.

### Best-effort loading, not fail-fast

`AcapProfileFileLoader`/`AcapProfileStore` deliberately do **not** mirror
`YamlPolicyFileLoader`/`PolicyDefinitionStore`'s fail-fast startup contract.
A location matching zero files is normal (ACAP profiles are opt-in per
agent); a malformed or duplicate individual profile file is logged and
skipped, not a startup-failing exception. This is safe specifically because
ACAP profiles are *additive* — the coarse `agentMcpToolCalls`/
`agentMcpToolHolds` layer (default-deny) is always still enforced regardless
of whether any given agent's ACAP profile loaded correctly; one bad file
degrades that one agent to coarse-only enforcement, not a security hole.

### Reload endpoints, deliberately not merged with the main policy reload

`AcapProfileStore.reload()` is exposed at its own `POST
/api/v1/admin/acap-profiles/reload` (+ `GET` to list), not folded into the
existing `POST /api/v1/{internal,admin}/policies/reload`. Keeps this stage's
blast radius to new code only — the existing, already-tested reload
controllers/tests are untouched. No `/api/v1/internal/**` counterpart yet:
unlike the main policy file, no `zt-agents`/ops-script consumer needs ACAP
data today.

### Demo wiring

New `acap-profiles/crm-account-health-emea-01.yaml` (EMEA, read-only,
field-scoped per resource). `zte-policies.yaml` additionally grants this
agent a coarse `update_deal` ALLOW specifically so the write-check has
something real to override — demonstrating the two-layer interaction (a
tool the coarse layer would allow, denied by the ACAP layer) rather than
just an absence of any grant at all, matching the demo script's own RED
point #3 exactly.

## Self-Criticism

- **Single territory per agent, not per-resource.** The source ACAP schema's
  `scope.read.allow[].filter.territory` is per-resource; this profile has
  one `territory` field applied uniformly. Fine for this demo's single-
  territory agent; a multi-territory agent needs a real schema change, not
  just a new YAML file.
- **Field check only fires when `fields` is explicitly present in the
  call.** A tool call that omits `fields` entirely gets no field-level
  opinion from this layer — the eventual tool implementation (Stage 5) is
  responsible for not defaulting to an over-broad projection. This is a
  real, load-bearing scope boundary this policy layer cannot see past
  without more information than the call itself carries.
- **`never` labels are hardcoded strings inside `AcapScopeEvaluator`, not
  data.** Faithful to the source ACAP JSON's *labels* (`bulk_export_contacts`,
  `change_record`, `read_outside_territory`, `fields.deny`) but not to a
  literal `never: [...]` array — an operator can't add a *new* never-reason
  without a code change. Acceptable since the four checks already cover
  every concrete `never` entry in the one real ACAP profile this demo has.
- **Best-effort loading means a silently-broken profile is discoverable
  only via gateway logs** — there's no Admin Console indicator yet that
  agent X's profile failed to load and it's running coarse-only. Deferred
  to Stage 6, which adds Admin Console visibility for ACAP profiles anyway.
- **No test coverage for the reload endpoint's controller layer** (`AdminAcapProfileController`)
  beyond what compiling against `AcapProfileStore`'s already-tested
  `reload()`/`all()` proves — matches this codebase's established
  precedent of relying on the IT suite for thin controller wiring rather
  than a dedicated `@WebFluxTest` for every admin controller (e.g.
  `AdminPolicyController` has none either).

## Consequences

- Zero behavior change for any agent without a profile file — `agent-a`/
  `agent-b` and every existing test pass unmodified.
- The demo's full 🟢/🟡/🔴 script is now implementable end-to-end at the
  policy layer (Stages 1 + 3); only the actual `hubspot-mcp` tool surface
  (Stage 5) and dashboard polish (Stage 4) remain to make it a live demo.
- `AcapProfile`'s deliberately narrow shape leaves Stage 6 (agent metadata,
  thresholds) a straightforward additive extension — new top-level fields
  with the same null-safe-accessor convention `PolicyDocument` already
  established, not a rewrite.
