# FEAT-13 — Admin Console

**Maturity:** Working
**Depends on:** FEAT-02 (its own API is policy-gated), FEAT-07, FEAT-08, FEAT-09, FEAT-10, FEAT-11
**Feeds:** operators
**Detail:** [SPECS §5.10](../SPECS.md) · [ADR-012](../adr/ADR-012-full-yaml-migration-and-admin-console.md), [ADR-025](../adr/ADR-025-gateway-openapi-documentation.md)

## What it does

The operator surface: the active policy set, the audit trail, synced
identities, the service registry, the approvals queue, governance reporting,
the executive dashboard and the gateway's own API reference — served by the
gateway itself, with no separate process to run.

## Why it matters

Everything else in the system is a mechanism; this is where a human sees the
mechanisms working. It is also the demo: showing a policy, triggering a
refusal and finding it in the trail thirty seconds later is what makes the
product concrete.

## Behaviour

**Given** an unauthenticated visitor, **when** they open the console, **then**
the page loads and immediately redirects them to the identity provider — the
static bundle is public, the data behind it is not.

**Given** a signed-in user without the administrator role, **when** the page
calls its API, **then** every call is refused with `403` while the page still
renders — the refusal comes from the gate, not from hidden buttons.

**Given** an edited policy file, **when** the operator triggers a reload from
the console, **then** the new rules take effect without a restart, or the
errors are shown and the previous set stays active (FEAT-02).

**Given** a policy rule naming an unknown identity, **when** the Policies tab
renders, **then** the rule is visibly flagged as orphaned.

**Given** a held call, **when** it appears in the Approvals tab, **then** it
carries the same context and the same two actions as the standalone Approval
Center — one queue, two doors (FEAT-06).

**Given** a held call's audit row with its `202` status, **when** the trail
renders, **then** it is coloured as a hold, not as a success — a held call
must never look like a completed one at a glance.

## Limits

- No policy editing in the UI: rules are edited as a file and reloaded.
- No pagination or search on the audit trail — it shows the latest rows.
- The bundle is heavy (the embedded API-reference viewer roughly tripled it).
- Everything is either "administrator" or the dashboard's audience roles;
  there is no finer console-level permission model.
