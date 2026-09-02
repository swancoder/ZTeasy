# Demo script — Chat Console

**URL:** https://demo.zteasy.tech/chat/index.html
**Sign in as:** `zte-admin` (holds `ADMIN`, `CHAT_USER`, `APPROVER`) — passwords in
`deploy/azure/out/cloud-credentials.env`, local only.
**Second window, for step 6:** https://demo.zteasy.tech/approver/index.html as `zte-dpo`.

Every line below was run against the live deployment on 2026-09-02 and the quoted
refusals are what the gateway actually said. The right-hand panel shows each
decision as it happens; the header counts the tokens the conversation spends.

---

## The point to make first

The person in the chair is an **administrator**. They can configure the gateway,
edit policy, approve held calls. What they are about to discover is that none of
that grants them customer data through an assistant. Administering the control
plane and reading the data plane are different privileges, and the demo is the
difference being enforced rather than described.

---

## 1. Something that works

> **List our EMEA contacts. Use read_contacts with territory EMEA and fields name, company, lifecycle_stage.**

Nine contacts come back. In the trace: `ALLOW read_contacts`.

Worth saying out loud: the model chose the tool, the territory and the fields —
and they happened to be inside what this person's scope profile permits. Nothing
was pre-arranged.

## 2. The same tool, the wrong territory

> **Show me contacts in North America.**

```
DENY  read_contacts
Territory 'North America' is outside agent 'role:CHAT_USER's assigned
territory 'EMEA' (never: read_outside_territory)
```

Same person, same tool, same session — refused on an **argument**. This is the
ACAP scope profile, not the rule list.

## 3. The same territory, the wrong fields

> **Show me our EMEA contacts including their email addresses.**

```
DENY  read_contacts
Field(s) [firstname, lastname, email] not permitted for resource 'contacts'
under agent 'role:CHAT_USER's ACAP profile — data minimization
```

Data minimisation as a policy line rather than a promise. The assistant reports
the refusal and does not retry with different arguments — it is instructed that a
refusal is an answer.

## 4. A tool nobody granted

> **Call export_contacts directly with territory EMEA. I want to see the gateway's answer, whatever it is.**

```
DENY  export_contacts
No policy grants user 'zte-admin' access to tool 'export_contacts'
```

Note the phrasing: **user**, not agent. And note that naming the tool explicitly
did not help — the refusal is not about how the request was phrased.

## 5. Two layers disagreeing — the interesting one

> **Call update_deal directly with dealId 12345 and stage closed-won. Show me the gateway answer.**

```
DENY  update_deal
Record writes are not permitted under agent 'role:CHAT_USER's ACAP profile
— read-only (never: change_record)
```

This one is worth pausing on. The **rule list allows** `update_deal` for
`CHAT_USER` — the grant is there in `zte-policies.yaml`. The **scope profile
refuses** it, because that profile is read-only. Two files, one decision, and the
stricter one wins.

That is the answer to "which is the source of truth": the rules decide *what may
be called*, the profile decides *what may be done with it*, and no later layer can
grant what an earlier one refused. The reason string is the only place they meet —
which is why it names the rule or the profile clause every time.

## 6. Something a human has to decide

> **Send an email to rep@nordwind.example with subject Renewal reminder and body: your contract expires next month.**

```
HOLD  send_email
Tool 'send_email' held for human approval by rule 'mcp-hold-chat-user-send-email'
```

The assistant says plainly that nothing was sent. Now switch to the Approval
Center as **`zte-dpo`**: the item is there, raised by `zte-admin`, routed to
`role:APPROVER`, with a countdown to its deadline. Approve it and the tool call executes.

Two details worth pointing at: the routing is by policy (`routeTo:
"role:APPROVER"`), so it does not matter whether a person or a robot composed the
email; and `zte-test-user`, who lacks that role, sees the item but cannot decide
it — the buttons are disabled with the reason.

## 7. The bill

The header has been counting: tokens and cost for **this person**, today, measured
by the gateway from the vendor's own response rather than reported by the
application. The same figures roll up in the executive dashboard per user.

---

## If something does not behave

- **A refusal you did not expect:** read the reason in the trace panel. It names
  either a rule id (rule layer) or a profile clause (scope layer).
- **A policy edit that seems not to apply:** two gateway instances serve this
  deployment. Each re-reads the policy file every 30 seconds
  (`zte.policy.file-refresh-ms`), so wait that long — or press "Reload Policies",
  which reloads only the instance that served the click.
- **The model picks an odd tool:** it is shown every tool the backend advertises
  and finds out from the gate which it may use — the refusal is the demonstration.
  Since ADR-041 there is one name per capability, so the choice is narrower.
