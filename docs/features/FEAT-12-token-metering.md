# FEAT-12 — LLM Token Metering

**Maturity:** Partial coverage (one reporter today — see Limits). Reported end to end in the cloud only since ADR-033: the reporter's TLS trust in the gateway had never been configured, so `llm_usage` stayed empty and every money panel read zero.
**Depends on:** components that make LLM calls and report them
**Feeds:** FEAT-11 (spend panels)
**Detail:** [ADR-029](../adr/ADR-029-executive-dashboard.md)

## What it does

Records what LLM usage actually cost: tokens in, tokens out, and the price
that applied at the time, attributed to the agent that spent them. The
in-house AI copilot reports its own usage automatically; any component inside
the perimeter can report its own through an internal endpoint.

Cost is stored, not computed at read time, so a report for a past period
keeps the price that was in effect then.

## Why it matters

Cost is the question a CFO asks first about an AI programme, and the one most
governance tooling cannot answer per-agent. Metering also makes the honesty
principle concrete: the system distinguishes "nothing was spent" from
"nothing was measured", and says which.

## Behaviour

**Given** an LLM call by an instrumented component, **when** the provider
returns token counts, **then** they are reported with the model, the purpose
and the computed cost — after the response is produced, never in its path.

**Given** a report with a missing agent or model, **when** it is received,
**then** it is rejected as a bad request.

**Given** a successful report, **when** it is accepted, **then** the response
says "accepted", not "created": the write is queued, and claiming otherwise
would overstate what has happened.

**Given** the metering store is unavailable, **when** a report arrives,
**then** the failure is logged and dropped — metering must never break or
delay the work that produced it.

**Given** a spend chart over a period with no usage on some days, **when** it
renders, **then** those days appear as zero rather than as gaps.

**Given** no usage has ever been recorded, **when** the panel is served,
**then** it explicitly reports that nothing has been instrumented.

## Limits

- **Only the in-house copilot reports today.** The MCP agents' own LLM calls
  happen outside this perimeter entirely, so their cost is invisible here —
  the gate sees their *tool calls*, not their model usage.
- Prices are operator configuration with placeholder defaults; a wrong
  default still renders as a confident number.
- Cost is computed by the reporter, so the gate trusts what it is told.
- No budgets, alerts or enforcement — this measures, it does not limit.
