# FEAT-15 — Deployment & Operations

**Maturity:** Working. State survives the nightly stop/start since ADR-033 (dump to an Azure Files share, automatic restore on start); it does not survive an unplanned crash between dumps.
**Depends on:** container images, a cloud subscription, DNS control
**Feeds:** every other feature's availability
**Detail:** [azure-deployment-plan.md](../azure-deployment-plan.md) · [ADR-027](../adr/ADR-027-azure-container-apps-deployment.md), [ADR-028](../adr/ADR-028-custom-domain-and-trusted-certificate.md)

## What it does

Makes the whole system deployable as containers behind one perimeter: every
component internal, exactly one browser-facing entry on a real domain with a
publicly-trusted certificate, and a separate certificate-preserving entry for
agents. Scripted end to end — provisioning, domain binding, image pushes —
plus a stop/start command so the stack can be parked overnight without losing
its addresses.

The same topology runs locally under compose, so it can be proven before
anything reaches the cloud.

## Why it matters

A governance product is judged on whether it looks like something an
organisation would run. A real domain and a real certificate change that
conversation entirely. The perimeter is also part of the product's claim:
agents, identity provider, database and tool backend are unreachable from
outside, and that is verifiable rather than asserted.

## Behaviour

**Given** the browser-facing entry, **when** a person opens the consoles,
**then** TLS is terminated by the platform with an auto-renewing certificate
and no warning.

**Given** the agent-facing entry, **when** an agent connects, **then** TLS is
passed through untouched so its client certificate reaches the gate
(FEAT-03) — which is why there are two entries rather than one.

**Given** an internal component (identity provider, database, tool backend),
**when** it is addressed from outside, **then** it is unreachable; it exists
only inside the environment.

**Given** the stop command, **when** it runs, **then** every component is
scaled to zero and compute billing stops, while the environment, storage and
public addresses survive — starting again needs no redeploy and no DNS
change.

**Given** the start command, **when** it runs, **then** components come up in
dependency order, database and identity provider first.

**Given** an identity-provider restart, **when** it re-imports its realm,
**then** its signing keys change and the gateways must be restarted to pick
them up — otherwise valid tokens are refused. This is a real operational
gotcha, documented rather than hidden.

## Limits

- **Durability is a dump, not a disk** (ADR-033): the database runs on
  ephemeral container storage because SMB Azure Files cannot host a Postgres
  data directory. `power.sh stop` dumps to a share and every start restores
  from it, so a planned stop keeps the audit trail, approvals, policy toggles
  and ACAP lifecycle — an unplanned crash loses whatever came after the last
  dump. Keycloak keeps no such backup and re-imports its realm on every start,
  which also invalidates sessions.
- Deployment is scripted but not declarative: no infrastructure-as-code, and
  re-running the scripts is the only reconciliation.
- Images are pushed under a single moving tag, so rollback means finding a
  digest rather than a previous tag.
- One region, one instance of everything; no redundancy.
