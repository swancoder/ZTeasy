# FEAT-01 — Zero Trust Request Gate

**Maturity:** Production-shaped (dev crypto — see Limits)
**Depends on:** FEAT-02 (policy), FEAT-03 (transport identity), FEAT-08 (identities)
**Feeds:** FEAT-07 (audit), every downstream service
**Detail:** [SPECS §4, §5.2](../SPECS.md) · [ADR-001](../adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md), [ADR-004](../adr/ADR-004-mtls-implementation.md)

## What it does

Every request that reaches a protected service has to answer four questions,
and each answer is produced by a distinct mechanism rather than inferred:

1. **Who is the user?** — a Keycloak-issued JWT, signature-verified at the gate.
2. **May they do this?** — a policy decision (FEAT-02), default deny.
3. **Who is the calling service?** — a client certificate (FEAT-03).
4. **On whose behalf?** — a short-lived on-behalf-of token the gate mints and
   downstream services validate, so "service A calls service B" never loses
   the human it started from.

Nothing is trusted because it is already inside the network. A service that
receives a call can verify all four properties itself.

## Why it matters

This is the difference between "we have an API gateway" and "we have zero
trust". The OBO token is the part that survives contact with reality: without
it, the identity of the person who triggered a chain of calls evaporates at
the first hop, and every audit trail downstream becomes "some service did
something".

## Behaviour

**Given** a request with a valid user JWT and a client certificate, **when**
policy allows the path, **then** the gate mints an OBO token, forwards the
request, and the downstream service validates that token's signature and
expiry before answering.

**Given** a request with no `Authorization` header, **when** it hits a
protected path, **then** it is refused with `401` by the security layer
before any policy work happens.

**Given** a valid JWT whose roles match no rule, **when** the request is
evaluated, **then** it is refused with `403` and the refusal names the rule
set that produced it (or its absence) in the audit trail.

**Given** a caller that presents an OBO token it forged itself, **when** a
downstream service validates it, **then** validation fails on the signature
and the call is refused — the token is not merely decoded.

**Given** an OBO token older than its 30-second lifetime, **when** it is
replayed, **then** it is refused as expired.

**Given** a client that supplies its own `X-ZTE-User-Context` or `X-User-Id`
header, **when** the request passes the gate, **then** those headers are
stripped and replaced — a caller cannot assert its own identity downstream.

## Limits

- The OBO token is signed with a **shared symmetric secret**: any component
  holding it can mint tokens for any user. Asymmetric signing is the named
  production upgrade (ADR-004).
- All services share **one client certificate**, so transport-level identity
  proves "an authorised component", not *which* one. Per-agent certificates
  (SPIFFE-shaped) remain future work.
- A request rejected for having no token at all never reaches the audit
  writer, so true `401`s are absent from the trail (ADR-013).
