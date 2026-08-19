# ADR-025: OpenAPI Documentation for the Gateway's Own API + Admin Console "Documentation" Tab

## Status
Accepted

## Context

`gateway-service` exposes a real REST API surface (`/api/v1/admin/**` — 10
controllers backing every Admin Console tab, plus `/api/v1/internal/**` for
zt-agents/ops tooling) with no machine-readable spec and no reference doc
beyond reading controller source or `docs/SPECS.md` §7 (API reference,
maintained by hand). `service-a`/`service-b` already carry
`springdoc-openapi-starter-webflux-ui` (added in ADR-016's amendment, purely
so this gateway's own Inventory auto-discovery could fetch their
`/v3/api-docs`) — the version-catalog alias already exists
(`libs.springdoc.openapi.webflux.ui`, 2.7.0), just unused by `gateway-service`
itself. Separately, the Admin Console already has a working
`swagger-ui-react` integration (`SchemaDrawer.tsx`, rendering *other*
registered services' discovered schemas on demand) — the UI-side dependency
and rendering pattern both pre-exist; nothing new needed inventing there.

Requested directly: publish the gateway's own API docs, in a dedicated
Admin Console pane.

## Decision

### springdoc on the gateway itself, zero per-endpoint annotation

Added `implementation(libs.springdoc.openapi.webflux.ui)` to
`gateway-service/build.gradle.kts` — same artifact/version already proven
against this exact Spring Boot 3.4.3 by service-a/b. Springdoc
auto-discovers every `@RestController` under `com.zte.gateway` from its
Spring MVC annotations alone; a single `OpenApiConfig` bean supplies only
the document-level `Info` block (title/description), not per-route
`@Operation` annotations — the existing controllers' method signatures and
`@RequestMapping`/`@GetMapping`/etc. already carry the shape springdoc needs.
Adding rich per-endpoint descriptions was in scope for "publish the docs
that exist," not for "write new docs" — deliberately not done here (see
Self-Criticism).

**Not covered:** `McpRouterConfig`'s two `RouterFunction` routes (`GET
/sse`, `POST /message`) — springdoc's functional-endpoint support needs
`@RouterOperation` annotations for a route to show up richly, and the
JSON-RPC-over-SSE protocol they carry doesn't fit REST-shaped docs well
regardless. Also not covered: the Spring Cloud Gateway proxy routes to
service-a/service-b (pass-through, not gateway-owned responses) and the
downstream MCP backend's own tool surface (already covered elsewhere — the
Registry tab's captured `tools/list` schema, ADR-016).

### New narrow-scope security chain, `permitAll`, matching the `/admin/**` precedent

Springdoc self-serves `/v3/api-docs` (spec JSON) and `/swagger-ui/**`
(its own bundled standalone UI) automatically — no new resource-handler
wiring needed, unlike the React bundle (which is built via `buildAdminUi`/
`processResources` and served by `AdminUiConfig`'s resource handler). But
both new paths need a security decision: `gateway-service` is
authenticated-by-default (`auth-library`'s `SecurityConfig`,
`anyExchange().authenticated()`), unlike service-a/b (which run
`anyExchange().permitAll()` entirely, mTLS being their whole trust
perimeter — ADR-016's prompts-hist entry 022 covers why that precedent
doesn't transfer directly here).

Added `ApiDocsSecurityConfig` (new file, `admin` package — co-located with
`AdminUiConfig` since both are "public docs/assets, not data" chains), a
third `@Order(-90)` `SecurityWebFilterChain` matching only
`/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, same shape as
`AdminUiConfig`'s and `InternalSecurityConfig`'s existing narrow-matcher
pattern. Chose `permitAll` over gating behind ADMIN auth: the spec describes
route *shapes* (paths, parameter names, request/response schemas), not
data, matching `/admin/**`'s existing "the SPA/UI is public, the JSON APIs
underneath stay protected" model rather than `/api/v1/admin/**`'s stricter
one. This does not widen access to any actual API path — every documented
endpoint stays exactly as protected as before.

### Admin Console: new "Documentation" tab, reusing `swagger-ui-react` directly

Fifth `View` value (`'documentation'`), fourth `<Tab>`, fourth conditional
render — the same four-touch-point pattern every prior tab addition has
used in `App.tsx`. New `Documentation.tsx` fetches `/v3/api-docs` and
renders it with `SwaggerUI spec={...}`, mirroring `SchemaDrawer.tsx`'s
existing fetch-then-render shape exactly — no new npm dependency (`
swagger-ui-react`/`@types/swagger-ui-react` already in `package.json`,
ADR-016). One deliberate deviation from every other tab's prop contract:
`Documentation` takes no `accessToken` prop. Every other tab needs one
because its data comes from an authenticated `/api/v1/admin/**` endpoint;
`/v3/api-docs` is `permitAll` by the decision above, so there is no token
to send.

## Self-Criticism

- **No per-endpoint `@Operation`/`@Parameter` descriptions.** Springdoc
  renders accurate route shapes (paths, methods, request/response DTOs via
  reflection) but no human-written "what this does" text beyond what a
  method/parameter name already implies — noticeably thinner than
  `docs/SPECS.md` §7's hand-written API reference. Acceptable as a first
  cut (the ask was to *publish* docs, not *author* them), but if this pane
  becomes the primary reference instead of a supplement to SPECS.md, the
  higher-traffic admin controllers would benefit from `@Operation` blocks.
- **`RouterFunction` MCP routes stay undocumented.** `GET /sse`/`POST
  /message` are a real, load-bearing part of the gateway's API surface and
  don't appear in the spec at all. Deliberately deferred, not silently
  dropped — flagged here rather than left implicit.
- **No dedicated unit test for `ApiDocsSecurityConfig`.** Matches existing
  precedent — neither `AdminUiConfig` nor `InternalSecurityConfig` has one
  either; this narrow class of `SecurityWebFilterChain` bean is verified
  manually/at the integration-test level in this codebase, not a gap
  introduced here. Verified live: `/v3/api-docs` returns 200 unauthenticated
  and lists all 10 admin controllers plus the 2 internal ones; `/api/v1/admin/**`
  itself still returns 401/403 without a valid ADMIN JWT, confirming the
  matcher didn't leak scope.

## Consequences

- The gateway's own API is now self-documenting from its controllers —
  adding a new `@RestController` automatically appears in `/v3/api-docs`
  and the Documentation tab with zero doc-sync step required (a genuine
  reduction in the existing "remember to update SPECS.md §7" maintenance
  burden for route-shape changes specifically; SPECS.md §7 still owns the
  narrative/why).
- Bundle-size cost already paid by ADR-016 (`swagger-ui-react` was already
  in the bundle for `SchemaDrawer.tsx`); this ADR adds no new frontend
  dependency, only a new backend one (`springdoc-openapi-starter-webflux-ui`,
  ~a few MB added to the gateway's own runtime classpath, not the browser
  bundle).
- `/v3/api-docs` and `/swagger-ui` added to `zte.audit.excluded-path-prefixes`
  (same class of noise as `/admin/`/`/actuator/` — static docs, no policy
  decision happens serving them).
