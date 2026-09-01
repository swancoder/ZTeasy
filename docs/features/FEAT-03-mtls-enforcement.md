# FEAT-03 — Smart mTLS Enforcement

**Maturity:** Production-shaped (one shared certificate — see Limits)
**Depends on:** dev CA and certificates (`certs/generate-certs.sh`)
**Feeds:** FEAT-01, FEAT-04 (transport identity for agent traffic)
**Detail:** [SPECS §5.2](../SPECS.md) · [ADR-018](../adr/ADR-018-smart-mtls-enforcement.md), [ADR-028](../adr/ADR-028-custom-domain-and-trusted-certificate.md)

## What it does

The gate requires a client certificate on the paths that carry machine
traffic — agent tool calls and proxied service calls — while leaving browser
paths (consoles, login, docs) on ordinary server-authenticated TLS. One port,
one listener, two postures, decided per path at the application layer.

## Why it matters

A bearer token can be stolen and replayed from anywhere; a client certificate
must be presented by something holding a private key. Requiring both for
agent traffic means a leaked agent token alone is not enough to reach the
tools. Requiring it *only* there is what keeps the product usable: an
approver opening a page in a browser cannot be expected to install a
certificate.

## Behaviour

**Given** an agent calling `/sse` or `/message` without a client certificate,
**when** the request arrives, **then** it is refused with `401` before any
JWT or policy work is done.

**Given** the same agent with a certificate but no token, **then** it is
still refused — the certificate proves the transport, not the authorisation.

**Given** a browser opening a console page or the login redirect, **when** no
certificate is presented, **then** the request proceeds normally; those
prefixes are excluded by construction.

**Given** the enforcement flag is disabled (integration tests), **when** any
path is called, **then** the filter is a no-op — the transport-layer setting
and this application-layer check are independent switches.

**Given** the deployment terminates TLS at a cloud ingress, **when** agents
connect, **then** they must use the TCP-passthrough entry point; a
TLS-terminating ingress would strip the certificate before the gate ever
sees it (this is why the cloud deployment has two front doors, FEAT-15).

## Limits

- All internal components share **one** client certificate, so this proves
  membership, not individual identity. Per-agent certificates would make the
  transport layer carry the same identity the JWT does.
- The dev CA is self-signed: browsers warn on the agent-facing address, and
  clients must be pointed at the CA or (in the demo) skip verification.
- Server certificates are read at startup — rotating them needs a restart.
- No mTLS on the last hop to the MCP backend; see FEAT-04's limits.
