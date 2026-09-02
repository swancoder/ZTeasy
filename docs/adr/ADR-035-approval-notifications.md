# ADR-035 — Approval notifications: who is told, and how anyone can check

**Status:** Accepted · 2026-09-02
**Context:** Stage 35 · completes the approvals work of ADR-019 / ADR-026 / ADR-034

## Context

After ADR-034 a held call has an owner and a deadline. Nobody is told about
either. The queue is polled every 15 seconds by whoever happens to have the page
open, which means the realistic outcome of a hold raised at 18:00 is that it
expires at 18:00 the next day, unseen. Adding a deadline without adding a way to
hear about it made the silence worse, not better: an item can now fail on a timer.

## Decision

### Who may decide is not who gets told

This is the whole design. ADR-034 deliberately left an unrouted call decidable by
any interactive user, and notifying everyone who *could* act reproduces the
bystander effect in software: six people see the item, each assumes one of the
others has it, and the deadline arrives.

So notification has its own addressing. A call's `routeTo` names its audience
when it has one; otherwise `zte.approvals.default-notify` does, `role:APPROVER`
by default. **Permission stays broad, responsibility is named.** The API exposes
both separately — `canDecide` and `addressedToYou` — and they genuinely differ:
for a plain `USER`, an unrouted call reads `canDecide: true, addressedToYou:
false`.

A role is resolved to people from the local identity cache
(`idp_identity_relations`, ADR-014), so addressing costs no IdP round trip. The
price: a role granted since the last sync (15 min) is not visible yet, which is
acceptable for addressing a message and is stated here rather than assumed.

### Two channels, chosen for what they actually fix

**In-app**, per viewer: a "N for you" badge on both approval surfaces, plus an
opt-in desktop notification. Permission is requested behind a button, never on
load — an unprompted browser dialog is how people learn to click "Block" — and
only items addressed to *this* viewer are announced.

**Outbound webhook**: the one path that reaches a person who is not looking at
the page. One body serves Slack, Teams and any JSON consumer: chat products
render `text` and ignore the rest, while `approval` carries the structure.

Email was considered and rejected for now: it needs an SMTP account and, worse,
it needs user email addresses in our database — a class of personal data the
identity cache has deliberately never held (ADR-014: "no secrets, just
id/type/name").

### The payload never contains the call's arguments

This hop leaves the perimeter for a third party. The arguments of a held call are
exactly the sensitive part — the recipient, subject and body of a customer email.
A gateway that masks fields in MCP responses (ADR-032) and then posts the same
content into a chat workspace would be lying about what it protects. The message
carries who, what, why, by when and a link; reading the arguments requires
authenticating to the Approval Center. A test asserts the absence, not just the
presence.

### Every attempt is recorded, including the ones that did nothing

`approval_notifications` (V20) stores one row per attempt: `SENT`, `FAILED` or
`SKIPPED`, with the audience, the usernames it resolved to, and the detail. Both
consoles show it per item.

Without this, "the approver was notified" is unfalsifiable and a webhook that
quietly 500s is indistinguishable from one that delivered. `SKIPPED` is stored
rather than omitted for the same reason: "no channel is configured" and "a rule
routes to a role nobody holds" are both *answers* to "why did nobody hear about
this", and an empty table would look like a system that tried nothing for reasons
unknown.

Delivery never blocks or fails the hold — the approval is already durable, and a
chat outage must not become a policy outage.

## Consequences

- A held call reaches a named human through a channel they already watch.
- "Was anyone told?" is answerable from the console, per item, after the fact.
- The distinction between permission and responsibility is now visible in the
  API, not just in an ADR.
- The webhook is a deliberate egress hop from the perimeter: the URL is a secret,
  the target is untrusted, and the payload is minimised accordingly.

## Self-critique

- **No retry.** A transient 502 loses that notification permanently; the row says
  `FAILED` and nothing tries again. A retry with backoff, or a sweep that
  re-notifies items whose only delivery failed, is the obvious next step and is
  not built.
- **No reminder before the deadline**, which was on the table and deliberately
  left out of this stage. It is the natural pair to ADR-034's expiry: an item
  notified once at 18:00 and expiring at 18:00 the next day gets no second chance
  to be seen.
- **One channel for everyone.** A single webhook URL means one destination for
  all audiences; routing `role:FINANCE` to a different channel than
  `role:APPROVER` is not expressible.
- **The badge only counts what the server told this viewer.** Someone who never
  opens the page still depends entirely on the webhook.
- **Group audiences remain unaddressable**, consistently with ADR-034 — a realm
  token carries no group claim, and the identity cache syncs membership but the
  entitlement side cannot evaluate it, so routing to a group would notify people
  who then could not decide.
- **Recipients are a point-in-time snapshot.** The row records who held the role
  when the message went out; a later grant does not retroactively appear, which
  is correct for an audit record and confusing if read as "who can decide now".
