# ADR-041 — Retiring the synthetic agents: the demo is a person

**Status:** Accepted · 2026-09-02
**Context:** Stage 42 · retires the demo surface of ADR-010, ADR-019 and ADR-020's agents

## Context

The demo had three simulated agents — `agent-a`, `agent-b` and the CRM
Account-Health Assistant — driven by a script that replayed a fixed sequence of
allowed, denied and held calls. They existed because there was no other way to
produce agent traffic.

ADR-039 gave the same tools to a person in a chat console, governed by the same
engine through a role-keyed ACAP profile. That made the agents redundant, and
worse than redundant: they carried a **second tool vocabulary**. `get_deals` and
`read_deals` sat next to each other on the policy screen, one DENY and one ALLOW,
reading as a contradiction; the pre-ACAP names had no resource convention, so the
scope evaluator could not tighten them at all. Someone reading that screen could
reasonably conclude the system was inconsistent — and the honest answer, "those
are two different tools, one of them ungovernable", is not an answer a demo should
need.

## Decision

The demo is the chat console. Removed:

- `agent-a` / `agent-b`: OIDC clients, ACAP profiles, policy rules.
- The CRM assistant's rules and profile from the **shipped** configuration.
- The pre-ACAP tool surface (`get_contacts`, `get_deals`, `update_deal_stage`,
  `export_all_data`) from the MCP backend, and the three DENY rules that existed
  only to refuse it with a reason.
- The `agent-runner` job, from Azure and from the provisioning scripts, and the
  agent service from the local cloud mirror.

What survives is one governed identity — `role:CHAT_USER` — carrying exactly the
shape the CRM agent's profile had: a territory, field-scoped reads, a write ban, a
daily threshold, lifecycle metadata, re-authorisation. That was always the point of
ADR-039: a person is governed like an agent was.

**The integration suite keeps an agent.** It has no browser and therefore no human
token, so it authenticates as `crm-account-health-emea-01` — whose OIDC client
remains — against its own policy document and profile fixtures under `src/it`. Tests
pin the engine's behaviour; they should not also pin what a demo happens to show,
and a demo edit should not break the suite.

## Consequences

- One name per capability. The policy screen no longer shows DENY and ALLOW for
  what reads as the same tool.
- Ten MCP rules instead of eighteen, one ACAP profile instead of four, and the
  Governance tab shows the identity the demo actually uses.
- No synthetic traffic: every row in the audit trail was caused by a person doing
  something, which is a better thing to show an audience than a script's output.

## Self-critique

- **The agent story is now told only by the code and the ADRs.** An audience that
  wants to see an autonomous agent refused has to be shown the chat instead and
  told the mechanism is identical. It is identical — same engine, same profile
  shape — but "identical" is now an assertion rather than a demonstration.
- **Coverage moved rather than grew.** Three integration tests that exercised the
  agent grants were deleted with the agents; the rest were repointed at fixtures.
  The engine paths they covered are still covered, but by fewer cases.
- **`crm-account-health-emea-01` still exists as a realm client** purely so the
  suite can authenticate. That is a demo artefact kept alive for tests, and it will
  confuse someone reading the Identities tab until it is renamed to something that
  says so.
- **Masking had to be fixed on the way**, and had been silently wrong: it looked a
  profile up by a single id, so a human governed by a role profile received an
  unmasked response. With the agents gone, that path is the only path — the bug
  would have shipped as "masking does nothing".
