# ADR-026: Standalone Approval Center — a Second UI Surface for the HOLD Queue

**Status:** Accepted
**Date:** 2026-08-25
**Stage:** 26

## Context

ADR-019 introduced the 🟡 HOLD outcome and a durable approval queue, decided
from the Admin Console's **Approvals** tab — gated by the `ADMIN` realm role
(`u2s-admin-console-api`). In practice the person who should decide a held
`send_email` is a business approver, not a gateway administrator, and giving
every approver the full Admin Console (policies, identities, registry CRUD)
just to reach one tab violates least privilege in the opposite direction.

Requirement: a separate interface at its own URL, with its own login screen
and an approve/decline view. For now, any authenticated user may decide —
role separation is a named follow-up, not part of this stage.

### THOUGHTS

- The queue, decision semantics, and audit trail already exist
  (`PendingApprovalService`) — this stage is purely a second *surface* over
  the same service. Two controllers, one decision path, one audit trail.
- "Access for all users" must not mean "access for all *identities*": an
  agent's client-credentials JWT is also "authenticated". Expressing the
  grant as two role-scoped `users2service` rules (`USER`, `ADMIN`) instead of
  `source: "*"` keeps agents out mechanically — their service-account JWTs
  carry no realm role — and makes the future tightening (a dedicated
  `APPROVER` role) a two-line YAML edit.
- A second SPA needs its own OIDC client so approver logins are
  distinguishable from Admin Console logins in Keycloak sessions and, via
  `decided_by`, in the audit trail.
- The SPA's hardcoded Keycloak authority (`http://localhost:8180/...`) was
  already a deployment liability (ADR-027 needs a reverse-proxied `/auth`);
  fixing it once here for both SPAs (`/ui-config.js`) avoids fixing it twice.

## Decision

1. **`zt-approver-ui/`** — a second, independent Vite/React/TS/MUI npm
   project (login gate + card-per-held-call queue with Approve/Decline,
   15s polling), built by `gateway-service`'s Gradle build (`buildApproverUi`,
   explicit `workingDir` since the `node{}` extension points at
   `zt-admin-ui`) and served at **`/approver/`** from
   `classpath:/static/approver/` (`ApproverUiConfig`, mirroring
   `AdminUiConfig`).
2. **Keycloak client `zte-approver-ui`** — public, authorization code +
   PKCE, redirect `https://localhost:8080/approver/*` (realm-export.json; an
   already-imported realm needs a live `kcadm create clients` — the
   `--import-realm` gotcha from SPECS §5.11 applies).
   `scripts/set-keycloak-password.sh` now also sets `zte-test-user`'s
   password, since a USER-role login is now a first-class flow.
3. **`/api/v1/approver/approvals[/{id}/approve|reject]`**
   (`ApproverApprovalsController`) — delegates to the same
   `PendingApprovalService`; `decided_by` = `preferred_username`. Enforced by
   `AdminAuthorizationFilter`, whose path check now covers both gateway-local
   API prefixes, against two new YAML rules (`u2s-approver-api-user`/
   `-admin`, target `approver`). Cert-free in `MtlsEnforcementWebFilter`
   (browser traffic), excluded from the request audit trail (the decision
   itself is already audited as an `APPROVED`/`REJECTED` MCP row).
4. **`GET /ui-config.js`** (`UiConfigController`, permitAll) — a one-line
   runtime snippet defining `window.ZTE_OIDC_AUTHORITY`
   (`zte.ui.oidc-authority`, env `ZTE_UI_OIDC_AUTHORITY`); both SPAs load it
   before their bundle and fall back to `http://localhost:8180/realms/zte-realm`.

## Alternatives considered

- **A `/approver` route inside zt-admin-ui** — one bundle, but the Admin
  Console's login is ADMIN-audience by convention and the bundle carries
  swagger-ui (~3× weight) an approver never needs; separate URL was an
  explicit requirement.
- **`source: "*"` policy rule** — rejected: would let agents approve their
  own held calls (see THOUGHTS).
- **Reusing the `zte-admin-ui` Keycloak client with an extra redirect** —
  faster, but approver logins become indistinguishable from admin logins.
- **A separate `ApproverAuthorizationFilter`** — a third instance of the
  `GlobalFilter` caveat's workaround with identical logic; extending the
  existing filter's prefix list is the smaller change (SPECS §8: extract
  shared logic once two call sites need the identical rule — they do, and it
  already exists).

### CRITIQUE

- Any USER can approve any held call — no per-approval routing (`route_to`
  is still stored-not-enforced, ADR-019), no APPROVER role, no notifications,
  no SLA/expiry. Accepted for this stage; explicitly queued as the next
  governance work item (with backend-hop auth).
- `AdminAuthorizationFilter`'s name now under-describes its scope (it also
  guards `/api/v1/approver/**`). Renaming would touch tests/docs for zero
  behavior change; documented in its Javadoc instead.
- The approver SPA copies `types.ts`/`ConfirmDialog.tsx`/`index.css` from
  zt-admin-ui rather than sharing a package — deliberate (two independent
  npm projects), but drift is possible; the `PendingApproval` mirror is
  annotated on both sides.
- 15s polling is crude next to SSE/websocket push — fine at demo scale,
  matches the Admin Console's manual-refresh posture.

## Consequences

- Approvers get `https://<gateway>/approver/` with their own login; the
  Admin Console tab keeps working unchanged.
- Every decision, from either surface, lands in the same audit trail with
  the deciding username.
- `/ui-config.js` makes both SPAs deployable behind any Keycloak topology
  without a rebuild (prerequisite for ADR-027's Azure `/auth` proxy).
