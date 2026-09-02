# FEAT-17 — Chat Console

**What it is:** a chat assistant with the CRM tools an agent has, and the same gate
in front of them. A person types a request, a model decides which tools to call,
and every one of those calls is a policy decision about *that person*.

**Maturity:** Working. Verified end to end on the live deployment; no budget
enforcement and no masking of what a person types on the way out (see Limits).

## Why it exists

Every other governance story here asks an audience to imagine an autonomous agent
misbehaving. This one puts them in the chair. People try the same things an agent
would — for better reasons and with worse discipline — and a refusal that happens
in front of the person who caused it is believed in a way a log line is not.

It also produces the number executives actually ask for: a token bill with a name
on it.

## How it behaves

1. The person signs in at `/chat/` with its own Keycloak client (`zte-chat-ui`)
   and needs the `CHAT_USER` realm role.
2. The chat backend (`zt-chat`) asks the MCP backend, through the gateway, what
   tools exist — **unfiltered**, so the model can try something it may not do.
3. It calls the model through the gateway, which holds the vendor key, meters the
   tokens from the vendor's own response and attributes the spend to the person.
4. Each tool the model chooses goes back through the gateway's MCP proxy, where
   the decision is made about the person: their roles, and the ACAP profile keyed
   to their role (`role:CHAT_USER` — EMEA only, a named field list, no writes).
5. ALLOW returns data. DENY comes back to the model as a refusal with its reason,
   which the assistant is instructed to report rather than work around. HOLD parks
   the call in the Approval Center, routed to whoever the rule names.
6. The right-hand panel shows the person their own decisions — scoped in SQL, so
   it is their trail and no one else's — and their own spend for the day.

## Dependencies

- [FEAT-02](FEAT-02-policy-engine.md) — the rules; ADR-039 made a person a valid subject
- [FEAT-04](FEAT-04-mcp-gate.md) — the MCP proxy the chat calls through
- [FEAT-05](FEAT-05-acap-scope-profiles.md) — scope, now keyable by role
- [FEAT-06](FEAT-06-human-approvals.md) — where a held call goes
- [FEAT-12](FEAT-12-token-metering.md) — spend, now measured by the gateway rather than self-reported

## Limits

- **Nothing filters what the person types on the way out.** ADR-032's masking
  applies to tool *responses*; a customer's data pasted into a chat message
  reaches the vendor. The gateway meters that call without inspecting it.
- **A meter, not a budget.** Spend is attributed and visible; nothing stops it.
  The ACAP threshold mechanism that escalates a tool call to HOLD on a daily limit
  is the obvious shape to apply to cost, and is not built.
- **The conversation lives in the browser** and is lost on refresh — deliberate
  (no server-side store of a person's CRM conversation), but it means the trace
  panel outlives the chat it explains.
- **Non-streaming**: the answer appears when it is complete. Streaming through a
  policy gate was judged the wrong first bet.
- **The tool list is deliberately unfiltered**, so a model is shown tools this
  person may not use. That is what makes the refusal visible, and it is a choice
  worth revisiting for a deployment that is not a demonstration.
