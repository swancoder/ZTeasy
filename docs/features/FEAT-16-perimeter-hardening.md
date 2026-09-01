# FEAT-16 — Perimeter Hardening

**Maturity:** Working
**Depends on:** FEAT-15 (defines the perimeter)
**Feeds:** the trustworthiness of every other feature
**Detail:** [azure-deployment-plan.md](../azure-deployment-plan.md) · [ADR-027](../adr/ADR-027-azure-container-apps-deployment.md), [ADR-030](../adr/ADR-030-credential-hygiene-and-identity-reconciliation.md)

## What it does

Closes the gaps that appear when a system designed for a private network
becomes publicly reachable: internal endpoints require a shared secret, the
identity provider's administrative surface is not published, and credentials
that open the live deployment exist only outside the repository.

Every item here exists because it was found open on the running deployment,
not because it was anticipated.

## Why it matters

The failure mode is always the same shape — an assumption that held on a
laptop ("only reachable on the local network") silently stops holding in
production. For a security product this is existential: the first thing a
knowledgeable visitor does is check whether the demo practises what it sells.

## Behaviour

**Given** an internal endpoint (policy read, policy reload, metering
reporting), **when** it is called without the shared secret in a deployment
that configures one, **then** it is refused — before any other work.

**Given** the same endpoint on a laptop with no secret configured, **when**
it is called, **then** it answers as before: local development is unchanged,
and the protection appears exactly when the deployment needs it.

**Given** a request for the identity provider's admin console or its master
realm through the public entry, **when** it arrives, **then** it is answered
with "not found" — not "forbidden", because a console that is not published
should not advertise that it exists.

**Given** the product realm's login and token endpoints, **when** they are
requested through the same public path, **then** they work normally — the
block is scoped to the administrative surface.

**Given** a deployment being provisioned, **when** credentials are needed,
**then** they must come from the environment; a missing one stops the
deployment with the variable named, rather than falling back to a value
committed in a public repository.

**Given** the repository itself, **when** it is searched for the credentials
that open the live deployment, **then** none are found — only obviously-local
development values that no reachable deployment uses.

## Limits

- The internal-endpoint secret is a **perimeter control, not
  authentication**: anything inside the network that learns the header can
  use it. Proper service authentication for those endpoints remains the named
  upgrade.
- An IP-based version of this control was tried first and measurably failed
  behind a pass-through ingress — worth remembering before anyone proposes it
  again.
- Credentials live in one local file, which is better than a public
  repository and worse than a secret manager.
- Superseded credentials remain in git history; rotation, not redaction, is
  what makes them harmless.
