# ADR-040 — One front door: merging the two gateways

**Status:** Accepted · 2026-09-02 · **reverses ADR-028's two-app split**

## Context

ADR-028 split the gateway into two Container Apps:

- `gateway` — TCP passthrough, so an agent's client certificate survives to the
  application, reachable only at `https://<azure-fqdn>:8080`;
- `gateway-web` — HTTP ingress, so browsers get `https://demo.zteasy.tech` with an
  Azure-managed, auto-renewing certificate.

The reasoning was that Azure's HTTP ingress terminates TLS, which destroys mTLS,
and TCP ingress cannot carry a custom domain. Both halves of that were true and
verified at the time.

What it cost was paid for months afterwards. Two processes meant two of every
in-memory thing — the policy document, the rule-activation overlay, the ACAP
lifecycle — and a series of defects that all had the same shape: a change applied
to one instance and not the other. Pressing "Reload Policies" reloaded the console's
instance while the agent-facing one, which actually decides MCP calls, kept the old
document. Suspending an agent in one app left the other allowing its calls. Each was
found in production, by someone noticing that the demo disagreed with itself.

## Decision

One app: `gateway-web`, on the custom domain, with
`ingress.clientCertificateMode: Accept`.

Azure's HTTP ingress **can** request a client certificate and forward it to the
application in Envoy's `X-Forwarded-Client-Cert` header. ADR-028 did not use this;
that is the fact that changed, not the platform.

`MtlsEnforcementWebFilter` now accepts either: a certificate presented directly to
the listener (local development, unchanged) or one relayed in that header. The
relayed path is not taken on faith:

- **`ForwardedClientCertificate` validates the chain against our own CA.** The edge
  accepts *any* client certificate — it has never heard of `ZTE-CA` — so without
  this, "presented a certificate" would mean "presented any certificate". A test
  flips one byte of a real certificate's signature and asserts it is refused.
- **The header cannot be forged from outside.** Measured before adopting the
  design: a request carrying a hand-written `X-Forwarded-Client-Cert` and no
  certificate arrived at the app with the header *absent*. The edge sanitises it
  and sets it only from the handshake.

`Accept` rather than `Require`: browsers hold no client certificate and must still
reach the console. Which paths demand one is decided by the gateway, per path, as
before.

## What this changes about the claim being demonstrated

Honestly: **TLS no longer terminates at the gate.** It terminates at the
perimeter's edge, which verifies possession of the private key during the
handshake and relays the certificate inward. The gate then checks that certificate
against our CA.

The demo sentence changes from "mTLS all the way to the gateway" to "mTLS to the
perimeter edge, with the certificate carried to the gate and verified there". That
is a real reduction — the edge is now inside the trusted computing base — and it
is the price of one address, one process and one truth. ADR-028 rejected exactly
this trade when the alternative was a header the proxy *asserts* with no way to
check it; the forgery test is what makes the trade different now.

## Consequences

- One process. The policy document, the activation overlay, the ACAP lifecycle and
  the MCP session map exist once. The convergence timers added in ADR-032 and
  ADR-039 remain correct but no longer paper over a split.
- One address: `https://demo.zteasy.tech` for browsers, agents, the chat backend
  and zt-agents alike. The `:8080` URL is gone.
- Internal callers now reach the gateway through its public name rather than the
  internal one, so they must trust the public issuer *and* our CA. Both Kotlin
  clients build a trust store from the JDK defaults plus `ca.crt`; the Python agent
  simulator now verifies TLS instead of disabling it, which the old self-signed
  listener had forced.
- The old `gateway` app is deactivated, not deleted — the split can be restored in
  one command if this trade turns out to be wrong for an audience.

Verified end to end after the merge: SSE handshake with a client certificate opens
through the single host and is refused without one; the chat console returns nine
EMEA contacts; an agent run produced 12 ALLOW / 3 DENY / 1 HOLD.

## Self-critique

- **Agent traffic now leaves the VNET and comes back.** Internal callers use the
  public FQDN because that is the listener with the client-certificate mode and the
  domain. It works and is measured, but it is egress where there used to be none,
  and an outage of the public endpoint is now an outage of internal traffic too.
  The internal ingress may support the same mode; that was not tested.
- **The edge is now trusted.** If Azure's ingress ever stopped sanitising the
  header, identity could be forged with a single request. Nothing in this design
  would notice. A second signal — mutual authentication at the application layer,
  or a shared secret between edge and app — would catch it, and does not exist.
- **`Accept` means every browser is asked for a certificate.** A user whose browser
  holds a client certificate may see a selection prompt. Not observed on the demo
  machine, not exhaustively tested across browsers.
- **One process is also one blast radius.** The split, whatever it cost, meant a
  browser-facing crash did not stop agents. That resilience is gone, and at one
  replica it was mostly theoretical anyway.
- **ADR-028 was not wrong when it was written.** It recorded a constraint that was
  real and a workaround that worked. The lesson is narrower and more uncomfortable:
  a platform capability nobody re-checked for months was the difference between one
  address and two, and the re-check took twenty minutes.
