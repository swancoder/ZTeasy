# ADR-029: Executive Dashboard — Role-Scoped Panels, Real Token Metering, and a Shared Visual Language

**Status:** Accepted
**Date:** 2026-08-27
**Stage:** 29

## Context

The consoles were built for operators: dense tables, one tab per subsystem.
The audiences that actually ask "is our agent estate under control?" — CEO,
CFO, CTO, Board/Risk, DPO — cannot read that, and the reference design they
brought is a light, card-based dashboard with KPI tiles, a spend chart, a
gate-decision split and an inline approval queue.

The reference design also shows figures this system has never measured: LLM
spend, token metering, a top-spender agent.

### THOUGHTS

- **Role tabs must not be decoration.** If the demo's whole claim is that
  policy decides access, then a CFO tab that anyone can click is a
  contradiction. The audiences are realm roles, the panels are separate API
  paths, and the existing YAML policy engine grants role→path — no new
  authorization mechanism, just rules.
- **Missing measurements are a data problem, not a rendering problem.**
  Faking spend numbers on a governance product is precisely the kind of thing
  the product exists to catch. Either measure it or say it isn't measured.
- Token usage is genuinely available: the Anthropic API returns
  `usage.input_tokens`/`output_tokens` on every call, and `zt-agents` already
  makes those calls. What was missing was somewhere to put it.
- The visual change is best expressed as a **theme**, not a rewrite: every
  existing table, dialog and chip inherits it, and the two SPAs stay
  identical without sharing a build (they are independent projects, ADR-026).

## Decision

**1. Audiences are realm roles.** New roles `CEO`, `CFO`, `CTO`, `BOARD`,
`DPO` (plus a demo user each). New API `/api/v1/dashboard/{summary,spend,
operations,risk,data-protection}`, enforced by the existing
`AdminAuthorizationFilter` (its prefix list now covers this third
gateway-local surface) against new `u2s-dashboard-*` rules. Each role is
granted only its own panel path; `CEO`/`BOARD`/`ADMIN` get the lot. The UI
hides tabs a user's roles don't cover, and treats a `403` as a legitimate
answer ("your role isn't granted this panel") rather than an error — the
refusal is the control, the tab is a courtesy.

**2. Real token metering.** New `llm_usage` table (Flyway `V14`),
`LlmMeteringService` (the codebase's standard async fire-and-forget writer,
SPECS §8), and `POST /api/v1/internal/metering/llm` for reporters. `zt-agents`
now reads `usage` off every Anthropic response and reports it, with pricing
as operator configuration (`anthropic.pricing.*`, micro-currency per 1k
tokens) — cost is *stored* at call time so a past window keeps the price that
applied then. Where nothing has been reported, `SpendPanel.instrumented` is
`false` and the UI says "not reported yet" instead of showing €0.

**3. One visual language.** A shared `theme.ts` in both SPAs: light surfaces,
hairline borders, 12px radius, pill buttons, and colour reserved for meaning
(green allow / amber hold / red deny, exported as `DECISION_COLORS`). The
old `index.css` — a fixed 1126px centred column with its own dark palette —
is reduced to a reset. The spend chart is ~60 lines of inline SVG rather than
a charting dependency (`zt-admin-ui`'s bundle is already heavy, SPECS §9).

## Alternatives considered

- **Tabs as pure UI filters over one endpoint.** Simpler, and it was the
  recommended option — rejected in favour of real roles because "the tab
  hides it" is not access control, and this product is about access control.
- **Demo values from config for spend.** Fastest path to the reference
  design; rejected: a governance dashboard that invents its own numbers is
  indefensible the first time someone asks whether they're real.
- **Deriving spend from MCP call counts.** Cheap, needs no reporting — but it
  would be a fabricated correlation: a tool call and an LLM call are
  different events with no fixed ratio.
- **Rewriting components in plain CSS** for pixel-exactness: rejected, it
  would mean re-implementing every table and dialog for a visual delta a
  theme already covers.

### CRITIQUE

- The `u2s-dashboard-*` rules are nine near-identical entries; a role→paths
  matrix would express this better, but that means extending the policy
  schema, which is a bigger change than this stage warrants. Revisit if a
  sixth audience appears.
- `zt-agents` is currently the only reporter, so "spend by agent" has one row
  in practice. The endpoint is open to any in-perimeter agent, but nothing
  yet reports on behalf of the MCP agents — their LLM calls happen outside
  this perimeter entirely, which is worth saying out loud rather than hiding
  behind an empty chart.
- Default pricing constants are a guess at EUR conversion and will be wrong
  for any real deployment; they are configuration precisely so they can be
  corrected, but a wrong default still renders as a confident number.
- `agentsGoverned/agentsSeen` counts agents seen in the audit window, so the
  denominator moves as traffic changes — deliberately (it measures reality,
  not configuration), but it means the tile can drop without anything being
  wrong.
- The dashboard adds a fourth fetch pattern to the admin SPA (`usePanel`)
  alongside three older ad-hoc ones; the older tabs were left alone this
  stage.

## Consequences

- Each audience gets a page it can read, and cannot fetch what its role isn't
  granted — verified live: CFO gets `200` on spend and `403` on operations,
  CTO the reverse, DPO only data-protection, plain `USER` `403` everywhere.
- The system now measures its own LLM cost, and says so honestly when it
  hasn't.
- Both consoles share one look, and future components inherit it by default.
