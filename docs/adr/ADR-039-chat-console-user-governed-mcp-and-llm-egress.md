# ADR-039 — A chat console: governing a person the way we governed an agent

**Status:** Accepted · 2026-09-02 · **Phases A and B of 4 implemented**
**Context:** Stage 39 · builds on ADR-010 (agent identity), ADR-020 (ACAP scope), ADR-029 (metering)

## Context

Every governance story this system tells is about an *agent*: an agent gets a
client identity, a scope profile, a lifecycle, and a gate in front of its tools.
The demo asks an audience to imagine an autonomous agent misbehaving.

The more realistic scenario — and the one asked for here — is a **person** with a
chat window and the same tools. They will try the same things an agent would, for
better reasons and with worse discipline, and every refusal is easier to believe
because the audience has been that person. It also produces something the agent
demo cannot: a token bill with a name on it.

## Decision

### The subject of the decision is the person

`McpPolicyEngine.evaluate(String agentId, ...)` derived its sources with
`IdentitySources.enrichClient` — the entire vocabulary was client-shaped. A chat
application dropped onto that unchanged would make every user one identity
(`client:zte-chat`), with one scope profile and one set of rules; the person would
appear only in the audit trail. Per-user governance would have been a caption.

So a caller is now either an agent or a person (`McpCaller`):

| | sources the matcher sees | ACAP lookup order |
|---|---|---|
| agent | `client:<id>` (+ its roles/groups) | `<id>` |
| person | `user:<name>`, each `role:<r>`, then `client:<app>` | `user:<name>`, then each `role:<r>` |

Two consequences worth stating. A rule can name the person, the role, **or the
application they arrived through — the front door is a source, not the subject.
And scope is authored once per role (`role:SALES_EMEA`) rather than per employee,
with a personal profile still winning where one exists.

**The agent path is byte-for-byte what it was.** Single-key profile lookup,
lifecycle keyed by the agent id, same `evaluate(String, ...)` entry point
delegating to the new one. This was deliberate: the load-bearing tests of
ADR-020/022/032 pass unchanged, which is the evidence that nothing about agent
governance moved while making room for people.

### The gateway is the exit to the model

The chat backend does not hold a vendor key. It posts to
`POST /api/v1/llm/messages` on the gateway, which injects the credential, calls
the vendor, and reads `usage` out of the response.

- **The key never leaves the gateway**, so a compromised front end leaks no model
  credential and no application can make a call the perimeter does not see.
- **Spend is measured, not declared.** Until now `zt-agents` reported its own
  token usage (ADR-029) — the gateway believed the component whose spend it was
  recording. Now the party that will be billed does the counting.
- **A model call is a policy decision**: `u2s-chat-user-llm` grants it, the same
  `AdminAuthorizationFilter` enforces it, and the same audit trail records it. It
  can therefore be denied, or made role-specific, like anything else.

### A person can read their own trail, and only their own

`GET /api/v1/me/events` returns the rows where the caller is the subject —
scoped **in SQL**, not filtered after reading, because an endpoint every
interactive user can reach must not fetch everyone's rows and then hide them.

### Shape

Served at `/chat/` from the browser-facing app with its own OIDC client and its
own realm role, with the tool-calling loop in its own backend container. Separated
by identity and policy rather than by hostname: a separate domain would look more
separate while being governed by exactly the same rules.

Non-streaming first. Streaming a model response through a policy gate is where
this kind of demo breaks, and the panel that matters — the trace — is not a
stream of tokens but a stream of decisions.

## Consequences

- The same YAML that governs `client:crm-account-health-emea-01` can govern
  `role:SALES_EMEA`, with the same ACAP scope, thresholds and lifecycle.
- Token spend becomes attributable to people, which is the number an executive
  actually asks about.
- A refusal now happens in front of the person who caused it, next to the message
  that caused it.

## Self-critique

- **This ADR describes four phases and one is built.** Phase A (identity, egress,
  own-events) is implemented and tested; the chat backend, the UI and the
  deployment are not. The status line says so rather than implying a finished
  feature.
- **`preferred_username` is how a person is recognised.** A client-credentials
  token that somehow carried that claim would be treated as a human. Keycloak does
  not issue such tokens, but the check is a claim inspection, not a proof.
- **Reading `usage` couples the gateway to a vendor's response shape.** The
  alternative was to keep trusting self-reported numbers, which is what this
  replaces. Named, accepted, and confined to one method.
- **A model call is metered but not budgeted.** Nothing stops a person from
  spending, and the natural next step — a per-role daily cost threshold — is
  exactly the ACAP threshold mechanism that already exists for tool calls.
- **The user's own-events feed trusts `agent_id`/`original_user_obo` to identify
  the person.** For MCP calls made by a human that is now their username; for
  older rows it is an agent id, so a user sees nothing from before this stage
  rather than something wrong.

## Phase B — the backend that runs the loop

`zt-chat` is a separate module and a separate container, and its shape is chosen
by what it must *not* be able to do.

- **It holds no model credential.** It posts to the gateway's egress endpoint,
  which injects the key. A compromised chat backend leaks nothing about the
  vendor account and cannot spend a token the perimeter did not count.
- **It is not given the ADR-038 hop certificate.** It holds the shared perimeter
  identity (`client.p12`), which lets it reach the gate and — by design — nothing
  past it. A chat backend able to call the MCP backend directly would be exactly
  the bypass ADR-038 closed.
- **It has no identity that can call a tool.** Every hop is made with the user's
  own bearer token, relayed per request. Without a user, the service can do
  nothing: the policy decision, the ACAP scope and the token bill are all about
  the person, and there is no fallback identity to fall back to.
- **It speaks the agent's transport**, `GET /sse` + `POST /message` with the
  result correlated off the event stream (ADR-009). A synchronous convenience path
  would have been less code and a second door into the same room.

### Tool discovery is not tool use

`tools/list` has no `params.name`, so the policy engine answered "Missing tool
name" — a caller could not discover the menu at all. It now passes through,
audited, unfiltered.

Filtering the list to what this person may call was the obvious alternative and is
the wrong choice here. The refusal *is* the demonstration: the model is shown
every tool, tries one it should not, and is told by the gate — in front of the
person who asked for it. Filtering would also teach a model that whatever it can
see it may do, which is a bad thing for a model to learn.

The system prompt tells the assistant that a refusal is an answer: report it, do
not retry with different arguments, do not reach for another tool that gets the
same data. Tests pin the rendering — a denial arrives as
`REFUSED BY ZTEASY POLICY: <reason>`, a hold as `HELD FOR HUMAN APPROVAL`, and
neither can be mistaken for an empty result.

### Bounded

Four tool rounds per message, then the assistant says it stopped. Every round is a
metered model call, so an unbounded loop spends real money; "the model will stop"
is not a budget.
