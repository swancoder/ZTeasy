# ZTeasy — Feature Catalogue

**What this document is:** the product-level view of ZTeasy. What each
capability does, why it matters, and — most importantly — how the
capabilities depend on each other. It is written for the people who extend
the system and decide what it should do next.

**What it is not:** an architecture reference or a decision log. How a thing
is built lives in [SPECS.md](SPECS.md); why it was built that way lives in
the [ADRs](adr/). This catalogue links to both and deliberately does not
repeat them — a fact stated in two places drifts in one of them.

Each feature has its own specification under [`features/`](features/),
covering observable behaviour (given/when/then, including refusals and edge
cases), its dependency map, and an honest maturity statement.

---

## The one-sentence product

ZTeasy is a Zero Trust gate for AI agents: every tool call an agent makes is
authenticated, checked against policy — including the call's *arguments* —
then allowed, refused, or routed to a human, and every outcome is recorded.

---

## Feature map

The arrows are "depends on / feeds": a feature points at what it needs, and
what it produces flows the other way.

```
                    ┌──────────────────────────────────────────────┐
                    │  FEAT-08 Identity Sync  (who exists)          │
                    └───────────────┬──────────────────────────────┘
                                    │ identities (URNs)
                                    ▼
  FEAT-01 ───────►  FEAT-02 Policy Engine  ◄──────── FEAT-09 Registry
  Request Gate      (who may do what)                (what exists to call)
      │                    │        ▲                       │
      │ identity           │        │ tightening            │ routes
      │ + OBO              │        │                       │
      ▼                    ▼        │                       ▼
  FEAT-03 mTLS      FEAT-04 MCP Gate ──── FEAT-05 ACAP Scope Profiles
  (transport        (tool-call             (argument-level limits,
   identity)         decisions)             usage thresholds)
                           │ ALLOW / DENY / HOLD
              ┌────────────┼───────────────┐
              ▼            ▼               ▼
      backend call   FEAT-07 Audit    FEAT-06 Approvals
                     (what happened)  (human decides)
                           │                │
              ┌────────────┴────────┐       │ decisions
              ▼                     ▼       ▼
      FEAT-10 Governance     FEAT-11 Executive Dashboard ◄── FEAT-12 Metering
      (per-agent history)    (role-scoped panels)            (token spend)
                           │
                           ▼
                    FEAT-13 Admin Console (operate all of it)

  Cross-cutting: FEAT-14 AI Copilot (reviews policy) ·
                 FEAT-15 Deployment & Operations ·
                 FEAT-16 Perimeter Hardening
```

---

## The catalogue

| # | Feature | In one line | Maturity |
|---|---|---|---|
| [FEAT-01](features/FEAT-01-zero-trust-request-gate.md) | Zero Trust Request Gate | Every request proves user identity, authorisation, service identity and on-whose-behalf | Production-shaped, dev crypto |
| [FEAT-02](features/FEAT-02-policy-engine.md) | YAML Policy Engine | One hot-reloadable file decides all four relationship categories, deny-by-default | Production-ready |
| [FEAT-03](features/FEAT-03-mtls-enforcement.md) | Smart mTLS Enforcement | Client certificates required on agent/service paths, not on browser paths | Production-shaped, shared cert |
| [FEAT-04](features/FEAT-04-mcp-gate.md) | MCP Tool-Call Gate | Intercepts every agent tool call and decides allow / deny / hold before the backend sees it | Production-shaped |
| [FEAT-05](features/FEAT-05-acap-scope-profiles.md) | ACAP Scope Profiles | Per-agent limits on arguments, fields, territory, writes and daily volume | Working, schema subset |
| [FEAT-06](features/FEAT-06-human-approvals.md) | Human-in-the-Loop Approvals | Held calls go to a durable queue and a dedicated approver UI, routed to a named owner, with a deadline and reminders | Working, no retry on a failed notification |
| [FEAT-07](features/FEAT-07-audit-trail.md) | Unified Audit Trail | One table records REST and agent traffic with correlation ids | Production-shaped |
| [FEAT-08](features/FEAT-08-identity-sync.md) | IdP Identity Sync | Mirrors Keycloak users/groups/roles/clients so policy can name them and orphans surface | Production-ready |
| [FEAT-09](features/FEAT-09-inventory-registry.md) | Service Registry & Dynamic Routing | Onboarded services become routable, discoverable and health-monitored without redeploy | Working, Spring-shaped health |
| [FEAT-10](features/FEAT-10-governance-reporting.md) | Governance Reporting | Per-agent allow/hold/deny history and an out-of-policy feed | Working |
| [FEAT-11](features/FEAT-11-executive-dashboard.md) | Executive Dashboard | Role-scoped panels for CEO/CFO/CTO/Board/DPO, enforced by policy | Working |
| [FEAT-12](features/FEAT-12-token-metering.md) | LLM Token Metering | Real token and cost accounting per agent, honest about what isn't measured | Partial coverage |
| [FEAT-13](features/FEAT-13-admin-console.md) | Admin Console | Operate policies, identities, registry, audit and approvals in one place | Working |
| [FEAT-14](features/FEAT-14-ai-policy-auditor.md) | AI Policy Auditor | An LLM reviews the active policy set and reports weaknesses | Demo-grade |
| [FEAT-15](features/FEAT-15-deployment-operations.md) | Deployment & Operations | Reproducible cloud deployment, custom domain, stop/start, one perimeter | Working |
| [FEAT-16](features/FEAT-16-perimeter-hardening.md) | Perimeter Hardening | Internal endpoints, IdP admin surface and credentials are closed by construction | Working |
| [FEAT-17](features/FEAT-17-chat-console.md) | Chat Console | A person uses the agents' CRM tools from a chat window, and every call is a policy decision about them — with their own trace and their own token bill beside it | Working, no budget enforcement |

---

## Reading order

- **New to the system:** FEAT-01 → FEAT-02 → FEAT-04 → FEAT-06. That is the
  product's spine: identity, rules, the agent gate, the human.
- **Evaluating governance claims:** FEAT-04 → FEAT-05 → FEAT-07 → FEAT-10.
- **Operating it:** FEAT-09 → FEAT-13 → FEAT-15 → FEAT-16.

## Maturity vocabulary

Used consistently in every specification, because a demo that overstates
itself is worse than one that admits its edges:

| Term | Means |
|---|---|
| **Production-ready** | Behaviour and failure modes are complete for real use at this scale |
| **Production-shaped** | The mechanism is right; a named production concern remains (e.g. shared dev certificates, symmetric token secret) |
| **Working** | Does what it claims, with known gaps listed in its own specification |
| **Partial coverage** | Correct where it applies, but does not yet cover everything a reader would assume |
| **Demo-grade** | Exists to demonstrate the idea; not built for unattended use |

---

*Catalogue current as of stage 30 (ADR-030). When a stage adds or changes a
capability, update the affected specification and this table — not SPECS.md's
architecture sections, which describe how, not what.*
