# ADR-016: APIM Inventory Registry — Auto-Discovery and Health Telemetry

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** ZTE-Lightweight Architects

---

## Context

`service-a`/`service-b` (and any MCP agent this gateway fronts) have existed
so far only as hardcoded `GatewayRouteConfig` routes and `zte-policies.yaml`
rule sources — there was no operator-facing inventory of what's actually
registered, no automated check that a newly onboarded service is reachable
and speaks the protocol it claims to, and no visibility into whether routed
traffic is actually succeeding over time. This adds a central APIM registry
(`inventory_services`), an auto-discovery worker that probes a service's
schema/tool list right after onboarding, a periodic health-ping job, and
passive `last_successful_call` tracking fed by real routed traffic — plus a
new "Registry" tab in the Admin Console.

---

## Decision

### Java + Reactor, not Kotlin — module language convention holds

The task's own Chain-of-Thought framing called for "an async Kotlin/Reactor
process." `gateway-service` is, and has been since Stage 1, a pure Java 21
module — `zt-agents` is the only Kotlin module in this repo, a deliberate,
narrow choice (a separate AI-copilot service, not part of the gateway's
core request path). Introducing Kotlin into `gateway-service` for one
worker class would be a real, disruptive architectural change for a single
feature, breaking a convention every other async component in this module
(`IdentitySyncService`, `KeycloakIdpAdapter`, `McpBackendClient`,
`HealthPollingService` itself) already follows. `AutoDiscoveryWorker` is
Java, using Project Reactor (`WebClient`/`Mono`) exactly like every sibling
component — "Reactor" from the task's own phrasing is honored; "Kotlin"
isn't, since the two aren't actually coupled requirements in this codebase.

### `health_metrics` is one current-state row per service, not a time series

`UNIQUE (service_id)`, upserted in place by both the health-poll job and
the passive traffic hook. The task's own column list (`last_ping_ms`,
`actuator_status`, `last_successful_call`, `updated_at` — all singular,
present-tense fields) already implies "current state," not a log; a
history table is a legitimate future extension (Future Migration Path) but
adds real schema/query complexity ("chart ping latency over time") the
task didn't ask for.

### Auto-discovery: `GET {base_url}/v3/api-docs` (REST), `POST {base_url}/message` `tools/list` (MCP)

The REST probe matches the task literally. The MCP probe is a real
interpretation call the task left open: "send a JSON-RPC `tools/list`
request" — but to what URL? This gateway's own MCP proxy uses a stateful
`GET /sse` handshake before any `POST /message` call
(`McpProxyHandler`/`McpSessionManager`). Requiring that same handshake for
a one-shot discovery probe would mean either faking a session or teaching
`AutoDiscoveryWorker` the full MCP session protocol for a single
call — real complexity discovery doesn't need. Decision: `AutoDiscoveryWorker`
POSTs directly to `{base_url}/message` with a bare `tools/list` JSON-RPC
envelope, on the assumption that schema discovery is stateless (matching
the URL shape `McpBackendClient` already uses for its own downstream call).
Named explicitly as an assumption, not a spec fact — MCP agents that
strictly require the session handshake even for `tools/list` would need a
different discovery strategy (Future Migration Path).

### `InventoryStatus`: `WARNING` is sticky; only `ACTIVE`↔`DOWN` self-heal

The task defines four statuses but only wires two transitions explicitly:
`PENDING`→`ACTIVE`/`WARNING` (discovery outcome). What resolves `DOWN`, or
un-sticks `WARNING`, was left unspecified — leaving `DOWN` as a defined but
unreachable enum value felt worse than a small, deliberate extension:
`HealthPollingService`'s periodic ping now also toggles `ACTIVE`↔`DOWN`
(reachable ping ⇒ `ACTIVE`, unreachable ⇒ `DOWN`), self-healing without any
operator action. `WARNING` is different in kind — it means the schema/tool
discovery itself failed or couldn't be confirmed, "a degraded state where
manual routing is required" in the task's own words — so a successful raw
health ping must **not** silently clear it; that would hide the real
problem (the API contract is still unconfirmed) behind an unrelated
green signal. `WARNING` only clears via a fresh discovery (re-onboarding,
or the planned "retry discovery" action — Future Migration Path).

### Health polling covers `ACTIVE`, `WARNING`, and `DOWN` — never `PENDING`

The task says "polls active/warning services"; `DOWN` is included too so a
downed service can recover automatically (see above) rather than being a
permanent dead end. `PENDING` is excluded — its `base_url` hasn't even
passed `AutoDiscoveryWorker`'s first probe yet, so pinging it is premature
noise, not a meaningful signal.

### `last_successful_call`: async fire-and-forget, resolved by service *name*, not gated by the audit exclusion list

Directly answers the task's own Self-Criticism: `HealthTelemetryService`
mirrors `RequestLogAuditService`'s exact architecture (`Sinks.Many` +
single `Schedulers.boundedElastic()` subscriber) — `RequestAuditFilter`'s
hot path does one non-blocking `tryEmitNext`, never awaits the DB write.
The upsert resolves `inventory_services.id` from the request's
`RequestTargetResolver`-derived target name via a `SELECT` subquery in the
same statement (`INSERT ... SELECT id FROM inventory_services WHERE
name = :name ... ON CONFLICT`) — one round trip, and a target name with no
matching registry row is a harmless no-op (not every routed path is
expected to be in the inventory). Deliberately **not** gated by
`AuditExclusionProperties` (the `request_logs` exclusion list, ADR-013) —
inventory health freshness is a different concern with its own no-op
safety net, and doesn't need or want that list's semantics.

### `InventoryService.list()` joins identities and health in memory, not via a native projected query

Spring Data R2DBC can, in principle, project a native `@Query` onto an
unannotated DTO record — but this project has no prior precedent using
that mechanism reliably, and getting it wrong is exactly the kind of
subtle, hard-to-unit-test failure mode this codebase has repeatedly hit
with R2DBC (`Mono<Void>` pitfalls, `@Modifying` vs. `RETURNING`, both
found live in prior sessions). `list()` instead fetches both repositories'
`findAll()`s and joins by `service_id` in a `Map`, in application code —
simpler, provably correct, and negligible cost at this project's MVP scale
(a handful of registered services), matching the same "don't add machinery
MVP scale doesn't need" bias `PolicyMatcher`'s full linear scan already
established.

### Plain MUI `Table`, not `@mui/x-data-grid`

The task's Task 5 literally says "MUI DataGrid." This repeatedly-reaffirmed
project decision (ADR-013's original call, reaffirmed in ADR-014/015/the
Identities UI ADR) rejects `@mui/x-data-grid` as an unproven dependency on
this MUI v9/React 19 combination, in favor of the same plain `Table`
pattern every other Admin Console tab already uses. Read literally, the
task asks for a data *grid* (rows and columns) — a plain `Table` satisfies
that functionally, at zero new dependency risk.

### `update()` always resets to `PENDING` and re-triggers discovery

The task didn't specify conditional re-discovery ("only if `base_url`
changed"). Always resetting is the simpler, provably-correct choice: it
can never leave a stale `ACTIVE` status pointing at a since-changed URL,
at the cost of one redundant discovery probe on a same-URL rename.

---

## Findings from live testing

### `InventoryService.update()`'s original `save()`-based implementation violated `created_at NOT NULL`

Found running this feature's own new integration test (`InventoryRegistryIT`'s
CRUD scenario), not caught by any mocked unit test: constructing a full
replacement `InventoryEntry` with `createdAt = null` for an update, then
calling `save()`, issues a plain `UPDATE ... SET created_at = NULL` (a
`save()` on a non-null-id entity is an UPDATE, not an INSERT, so the
column's `DEFAULT NOW()` never applies) — a `NOT NULL` constraint
violation, surfaced to the caller as an opaque `500`. Fixed with a scoped
`InventoryRepository.updateFields(...)` `@Query` touching only the
operator-editable columns, leaving `created_at` untouched — the same "don't
`save()` a partial/reconstructed entity, use a scoped `@Query` instead"
lesson `updateStatus` already applied for a different reason (avoiding a
read-then-write race), now shown to matter for column-completeness too.

### `Iterable` isn't a valid Spring Data R2DBC derived-query parameter type

`findByServiceIdIn(Iterable<UUID>)` failed application-context startup —
`IN` derived queries require `Collection`, not the more general
`Iterable`, even though `List` satisfies both. Fixed by narrowing the
parameter type. A real, if narrow, Spring Data R2DBC API constraint worth
naming since it's not obvious from the method-naming convention alone.

---

## Amendment (same day): correcting the mTLS premise, and OpenAPI on service-a/b

A follow-up task, filed after live-testing this feature, was framed as
fixing "a security vulnerability: `AutoDiscoveryWorker` and
`HealthPollingService` use a plain `WebClient`, bypassing our mTLS
perimeter." **That premise is incorrect** — investigated and disproven
before writing any code, not assumed either way:

- `MtlsHttpClientConfig` (unchanged since ADR-004) registers the
  application's **one** `ReactorClientHttpConnector` bean. Spring Boot's own
  `ClientHttpConnectorAutoConfiguration` — bytecode-inspected directly,
  not just recalled from documentation — provides a `WebClientCustomizer`
  bean, conditional on exactly this kind of singleton connector bean
  existing, whose body is `builder -> builder.clientConnector(connector)`.
  `WebClientAutoConfiguration`'s own default `WebClient.Builder` bean applies
  every such customizer. The net effect: **every** `WebClient.Builder`
  autowired anywhere in `gateway-service` — including `AutoDiscoveryWorker`'s
  and `HealthPollingService`'s, injected exactly the same way
  `KeycloakIdpAdapter`/`McpBackendClient` already were — already carries the
  gateway's mTLS client certificate, with zero code in either class asking
  for it explicitly.
- Confirmed live, not just by reading bytecode: `curl` with no client
  certificate against `service-a`'s `client-auth: need` listener
  (`https://localhost:8081`) fails at the TLS handshake — no HTTP response
  at all (`Request CERT` sent, connection then fails). `AutoDiscoveryWorker`'s
  probe against the exact same URL, from the real running gateway, received
  a genuine HTTP-level `404` — only possible if a valid client certificate
  had already been presented during that TLS handshake. The `WARNING`
  status this session originally observed for `service-a` was never a
  security gap; it was `service-a` genuinely having no `/v3/api-docs`
  endpoint to discover.
- This also resolves the task's stated Self-Criticism concern by
  construction: because the *existing* default `WebClient.Builder` already
  carries mTLS, no new hardcoded host/port was introduced anywhere.

**What actually changed:** nothing in the `WebClient` wiring — introducing a
second, explicit `ReactorClientHttpConnector` injection would have been
pure duplication of already-correct behavior, and a real regression risk
(the connector bean only exists when `zte.mtls.enabled=true`; the `it` test
profile deliberately sets it `false` so CI needs no certs — a naive hard
dependency would have broken every integration test's `ApplicationContext`
startup). Instead: `AutoDiscoveryWorker`/`HealthPollingService`'s Javadoc
now states this mTLS-inheritance fact explicitly, with the verification
method, so a future reader (or task author) doesn't have to re-derive it —
directly serving the real underlying goal of the task that raised this,
without introducing risk for a problem that didn't exist.

**What did need a real fix:** `service-a`/`service-b` had no `/v3/api-docs`
endpoint at all — a genuine gap, not a false alarm. Added
`springdoc-openapi-starter-webflux-ui` to both modules (`gradle/libs.versions.toml`
+ each `build.gradle.kts`). No `SecurityConfig` change was needed: both
services already run their own `ServiceSecurityConfig`
(`anyExchange().permitAll()` — mTLS is already their entire trust
perimeter, the shared `auth-library.SecurityConfig`'s JWT requirement
doesn't apply to either), so springdoc's auto-registered `/v3/api-docs`
route is reachable the moment the dependency is added, with zero
authorization wiring — `AutoDiscoveryWorker`'s probe (which, being an
internal, pre-user-context gateway process, has no JWT to present anyway)
now succeeds and correctly flips a freshly re-onboarded `service-a`/`service-b`
to `ACTIVE`.

**`InventoryRegistryIT` deliberately not changed for this.** The task's own
instruction assumed this IT tests directly against real `service-a`/`service-b`
processes — it doesn't; per `BaseZteIntegrationTest`, `service-a.uri`/
`service-b.uri` point at an in-process WireMock stand-in, decoupled from
this repo's actual `service-a`/`service-b` modules by design (so the IT
suite needs no real service-a/b process, cert setup, or Docker service to
run). The existing `onboardRestService_discoverySucceeds_becomesActive`
(stubs `/v3/api-docs` → 200) and `onboardRestService_discoveryFails_becomesWarning`
(no stub → 404) tests already cover both outcomes correctly and remain
valid — a registered service that genuinely lacks OpenAPI docs must still
land on `WARNING`, regardless of whether this repo's own two example
services now happen to have them. This feature's specific claim — that
`service-a` now really exposes `/v3/api-docs` — was verified live instead
(see the Git commit's own verification notes), matching the task's own
literal Verification section, which itself asks for a live `bootRun`+`curl`
check, not a new automated test.

---

## Amendment (2026-08-11): `management_url` for health polling on a separate port

Filed as a follow-up after the mTLS/OpenAPI amendment above was verified
live: with `service-a` correctly reaching `ACTIVE` via `/v3/api-docs`,
`HealthPollingService`'s very next poll cycle flipped it straight to
`DOWN`. Root cause, found live before writing any code: `pingOne` pings
`{base_url}/actuator/health`, but `service-a`/`service-b` only expose
`/actuator/health` on a **separate plain-HTTP management port** (9081/9082)
— confirmed by `curl`ing `/actuator/health` on the mTLS API port (8081)
with a valid client certificate and getting a `404` (the endpoint simply
isn't registered there), while the same request against the management
port returns `200`. This is a pre-existing gap dating to this ADR's
original Stage 16 implementation, not a regression from the mTLS/OpenAPI
amendment — the two happened to surface together only because that
amendment was the first time this repo's own `service-a` was registered
and watched through a full discovery + health-poll cycle.

**The task that filed this asked for two alternative fixes** ("target the
secure management endpoint... or update service registration to account
for management base URLs") **and separately asked verification to prove
the poll succeeds "over mTLS."** Investigated before picking one:
`service-a`/`service-b`'s management port is plain HTTP **by deliberate,
pre-existing design** — `application.yml` comments it explicitly ("no
client cert required for health probes"), and `docker-compose.yml`'s own
container `healthcheck` already depends on exactly that (`wget` with no
client certificate). Forcing that port to require mTLS would mean also
reworking the Docker healthcheck to present a client certificate — a
second, unrelated, and much larger change than "fix health polling,"
directly conflicting with this task's own "keep the change minimal"
instruction.

**What was built instead:** an optional `management_url` column
(`inventory_services`, `V9__add_inventory_management_url.sql`, nullable —
existing rows and every `InventoryRegistryIT` WireMock-backed test are
unaffected). `HealthPollingService.healthCheckUrl(entry)` — a small, pure,
package-visible static method with a direct unit test, same precedent
`statusTransition` established — pings `managementUrl` when set, else
falls back to `baseUrl` exactly as before. This resolves the task's
"correctly targeted" branch without hardcoding a scheme: `webClientBuilder`
carries the gateway's mTLS connector regardless of which URL is requested,
so an operator whose service *does* protect its management port with mTLS
can still set `managementUrl` to an `https://` address and get exactly the
"over mTLS" behavior the task's verification section asked for — `service-a`/
`service-b` just don't happen to be configured that way today, and making
them so is out of scope here (would mean adding `management.ssl.enabled=true`
+ `client-auth: need` to both services, and giving the Docker healthcheck
a client certificate — a real, larger change, tracked as a `docs/SPECS.md`
§9.2 backlog item rather than done as part of "fix the port mismatch").

**Verified live:** registered `service-a` at `base_url=https://localhost:8081`,
`management_url=http://localhost:9081` via the real running gateway's
`/api/v1/admin/inventory`; `AutoDiscoveryWorker` reached `ACTIVE` as before,
and the next `HealthPollingService` poll cycle correctly pinged the
management port and kept it `ACTIVE` (actuator `UP`) instead of flipping to
`DOWN`.

---

## Amendment (2026-08-12): capturing discovery payloads — an API Catalog

`AutoDiscoveryWorker` only ever checked reachability of `/v3/api-docs`/
`tools/list` and discarded the response body. This amendment captures it —
`inventory_services.discovered_schema JSONB` (`V10__add_discovered_schema.sql`)
— and adds an on-demand Admin Console viewer (Swagger UI for REST, a plain
tool list for MCP), evolving the registry from "is this reachable" into a
minimal API catalog.

**R2DBC ↔ `jsonb` mapping, investigated before writing any code** — this
project has no prior JSONB column anywhere, so nothing to copy. Decompiled
`r2dbc-postgresql:1.0.7.RELEASE`'s codec classes (`javap`) rather than
assuming: `DefaultCodecs` registers a dedicated `JsonStringCodec` that
decodes `json`/`jsonb` columns straight into `java.lang.String` — reads
need no custom converter. Writes are the asymmetric half: the driver's
default bind for a `String` parameter is `VARCHAR`, and Postgres rejects
an implicit `varchar -> jsonb` assignment in a parameterized `UPDATE`, so
`InventoryRepository.updateDiscoveredSchema` uses an explicit
`CAST(:schema AS jsonb)` in a native `@Query` — the task's own
Self-Criticism suggested exactly this mitigation; the bytecode inspection
is what confirms *why* it's the read/write-asymmetric fix needed, not a
blind copy of the suggestion.

**Kept `discovered_schema` off `InventoryEntry`/`InventoryView`/`findAll()`
entirely** — `InventoryRepository` gets two new methods
(`updateDiscoveredSchema`, `findDiscoveredSchemaById`) as native,
single-purpose queries independent of the entity mapping the list/CRUD
path uses. This satisfies the task's explicit "don't degrade the list
view" goal *by construction*: the registry list literally never selects
this column, rather than relying on every future caller to remember not
to over-fetch it. Net effect: zero changes to `InventoryEntry`,
`InventoryView`, `AdminInventoryController.InventoryRequest`, or the
frontend's `InventoryEntry` type — this amendment's blast radius on
already-working code is `AutoDiscoveryWorker` (genuine logic change) plus
one new endpoint/repository-method pair.

**Two correctness gaps found and closed before they could bite, not
after:**
1. Switching `probeRestApiDocs`/`probeMcpToolsList` from
   `.toBodilessEntity()` to `.retrieve().bodyToMono(String.class)` (needed
   to actually read the body) changes behavior on a genuinely empty
   response: `toBodilessEntity()` always emits exactly one element
   regardless of body length; `bodyToMono(String.class)` on a 0-byte body
   completes **empty** — no element at all. That would have silently
   broken the existing "2xx with an empty body is still `ACTIVE`" case
   (the CRUD test's onboard stub does exactly this). Fixed with
   `.defaultIfEmpty("")` immediately after `bodyToMono`, restoring the
   original always-one-element guarantee.
2. A target returning `200` with a non-JSON body (an HTML error page at
   `/v3/api-docs`, say) would make Postgres reject
   `CAST(text AS jsonb)`. Guarded with `ObjectMapper#readTree` before ever
   calling `updateDiscoveredSchema` — invalid or blank bodies (including
   the empty-body case above) skip the schema write but still let the
   `status` write through unaffected, rather than risking the whole
   discovery outcome on a payload this gateway doesn't control the shape
   of.

**Testing:** `AutoDiscoveryWorker` still has no mocked-`WebClient` unit
test (unchanged precedent — proven only via `InventoryRegistryIT`, same as
`KeycloakIdpAdapter`/`McpBackendClient`). Four new IT cases added: REST
capture round-trip, MCP capture round-trip, and `404` for both a
`WARNING` service (nothing captured) and an unknown `id`. The two capture
tests initially failed on their first real run — not a broken pipeline,
but an over-strict assertion: Postgres's `jsonb` column canonicalizes on
write (reorders keys, normalizes whitespace), so the round-tripped body is
valid-but-reformatted JSON, not the original bytes. Found live, not
anticipated; fixed by comparing parsed Jackson `JsonNode` trees
(structural equality) instead of raw strings — which doubles as
independent confirmation that the write(`CAST`)→read(`JsonStringCodec`)
round trip is semantically correct, not merely exception-free.

**Frontend:** `swagger-ui-react@5.32.13` + `@types/swagger-ui-react@5.18.0`,
as specified. `npm install` surfaced a real detail worth recording: a
transitive dependency (`react-inspector@6.0.2`) declares a React 16-18
peer range, not 19 — npm resolved it anyway (a warning, not a hard
failure), and both `tsc -b` and `vite build` succeeded cleanly, so left
as-is rather than forcing an unrequested override. Also worth recording
plainly: this dependency roughly **tripled the built bundle** (589 KB →
1.88 MB raw, 177 KB → 538 KB gzipped) — a direct, known cost of the
library choice the task specified; not mitigated here (e.g. via dynamic
import/code-splitting) since that wasn't asked for and this codebase's
established bias is against unrequested infrastructure — named explicitly
below (Self-Criticism) instead of left as a silent surprise.

New `SchemaDrawer.tsx` (kept out of `Inventory.tsx` to avoid growing that
file past its existing CRUD-table-plus-dialog scope): fetches the schema
only when a row's new "View Schema" button is clicked (on-demand,
matching the backend), `JSON.parse`s it inside a `try`/`catch` — falling
back to a raw-text `<pre>` block with an error `Alert` on parse failure,
the task's own Self-Criticism ask — then branches on `targetType`:
`<SwaggerUI spec={parsed}/>` for `REST`, a small defensive tree-walk
extracting `result.tools[]` (guarded with `typeof`/`Array.isArray` checks
throughout, since this is an external service's response shape, not
something this codebase controls) into an MUI `List` for `MCP`.

---

## Amendment (2026-08-12, second): custom `docs_url` + synchronous fetch

A direct follow-up to the schema-capture amendment above, adding operator
control over where `REST` discovery looks (`docs_url`, an optional full
absolute URL, `REST`-only — no equivalent exists for `MCP`'s fixed
`tools/list` convention) and a UI-triggered synchronous fetch
(`POST .../inventory/{id}/schema/fetch`) alongside the existing
passive/background one.

**The task's own suggested Self-Criticism mitigation — "use
`status === 'ACTIVE'`... for simplicity" to gate the Admin Console's "View
Schema" button — was investigated and found incorrect against this
codebase's actual behavior, before writing any code.** The prior
amendment's `discoverAndUpdateStatus` marks `ACTIVE` on *any* 2xx
response, but only writes `discovered_schema` when the body is valid,
non-blank JSON (`isValidJson(...)` — a deliberate decision from that same
amendment, not new). A target returning `200` with an empty body or a
non-JSON page reaches `ACTIVE` while capturing nothing — not hypothetical:
the pre-existing `crud_updateAndDelete` IT test's stub already does
exactly this (a bare `200`, no body), and this very amendment makes the
non-JSON case *more* likely by letting an operator type an arbitrary
`docs_url` that might land on the wrong page entirely. The task itself
named the correct alternative in the same breath ("or the backend must
project a `has_schema` boolean into the list view") and picked the wrong
one for expediency; implemented that alternative instead:
`InventoryView.hasSchema` (boolean), computed via a new, cheap
`InventoryRepository.findIdsWithDiscoveredSchema()` query (`SELECT id
WHERE discovered_schema IS NOT NULL` — never the payload), joined in
memory in `InventoryService.list()` the same way `HealthMetric` already
is. Satisfies the actual goal (correctly gate "View Schema") without
reintroducing the bandwidth cost the earlier amendment deliberately
avoided, and without a real correctness gap.

**`docs_url`** (`VARCHAR(512)`, not the task's suggested `VARCHAR(255)` —
matched to `base_url`/`management_url`'s existing sizing) threaded through
`InventoryEntry`, `InventoryRepository.updateFields`,
`InventoryService.create`/`update`, `AdminInventoryController.InventoryRequest`,
and the Admin Console form/table. `AutoDiscoveryWorker` uses it as-is (a
full absolute URL passed directly to `WebClient.uri(String)`, not a path
suffix) when non-blank; otherwise falls back to `{base_url}/v3/api-docs`,
now with a trailing-slash guard on `base_url` (plain string concatenation
doesn't self-normalize a resulting double slash the way the old
`WebClient.Builder#baseUrl(...)`-based construction implicitly did — a
small robustness fix made in passing, not separately requested).

**Reusable fetch-and-save logic, extracted but deliberately not
identical between callers.** `fetchBody(entry)` is the one shared HTTP
primitive (URL resolution, the `WebClient` call, the timeout). On top of
it, `discoverAndUpdateStatus` (existing, background path) keeps its
original lenient behavior unchanged — any 2xx is `ACTIVE` regardless of
body validity, any failure is `WARNING`, never an exception. The new
`fetchSchemaNow` (synchronous, UI-triggered path) is deliberately
*stricter*: a 2xx with an empty or non-JSON body is a **failure**
(`SchemaFetchException`), not a silent `ACTIVE`-with-nothing-captured —
an operator who just clicked "Fetch" needs a real yes/no answer, not the
background worker's "reachable enough to route" tolerance. This asymmetry
is intentional, stated in `AutoDiscoveryWorker`'s Javadoc, and directly
exercised by one IT test that hits both entry points against the
identical non-JSON stub in a single flow
(`fetchSchemaNow_invalidJsonBody_returns502_evenThoughBackgroundWorkerMarksActive`)
— proof it's a deliberate difference, not an inconsistency.

**HTTP status for a failed synchronous fetch: `502 Bad Gateway`, not the
task's literal "400/500."** A deliberate, minor, named deviation: `502` is
the standard, correct code for "this gateway couldn't get a valid
response from an upstream it proxies to," and that's exactly what
happened — the client's own `POST` was fine (ruling out `400`), and
nothing internally failed (ruling out `500`). `404` (unknown `id`) is
exactly as asked, via a new `ServiceNotFoundException`.

**Verified live**, against the real running gateway, real Postgres, and
real `service-a`/`service-b`: `service-b` — registered before this
amendment's code existed, so it had never had a schema captured —
independently confirmed the exact gap this amendment closes: `status:
ACTIVE`, `hasSchema: false`. `POST .../schema/fetch` against it returned
`200` and `hasSchema` flipped to `true` immediately, no wait for the
background scheduler. The same endpoint against an intentionally
unreachable target (`http://localhost:1`, connection refused) returned
`502` with `{"error":"Could not reach target: ..."}`. A fresh entry
registered with `docs_url` explicitly set round-tripped correctly through
`create`/discovery and reached `ACTIVE`/`hasSchema: true`.

---

## Amendment (2026-08-12, third): inline Edit + confirmed refetch-overwrite

Frontend-only — the backend already fully supported everything this
needed. Verified, not assumed, before touching any UI code:
`InventoryService.update`/`InventoryRepository.updateFields` already
threaded `docsUrl`/`managementUrl` through (added by the prior amendment),
and `updateDiscoveredSchema` is a plain, unconditional
`UPDATE ... SET discovered_schema = CAST(:schema AS jsonb) WHERE id = :id`
— there's no uniqueness constraint or prior-value check on that column at
all, so "does refetch overwrite without conflict" was already
unconditionally true. Confirmed live: fetched a real entry's schema twice
in a row and the second call still genuinely re-probes and returns `200`.

**Admin Console:** a new "Edit" (✏️) row action opens the existing
onboarding `Dialog` pre-filled from the row (`editingService` state, one
`InventoryEntry | null`) and submits via `PUT` instead of `POST`; title,
button label, and success message all branch on whether an entry is being
edited. Closed a real, if previously harmless, gap the task's own
Self-Criticism flagged: the dialog only ever reset its form state on a
*successful* submit — Cancel and backdrop-dismiss left old values sitting
in the form. With only one dialog mode that was invisible (an abandoned
onboarding attempt's leftovers, gone the next time you actually opened
it); with an Edit mode added, the same gap would let a cancelled edit's
values leak into a subsequent fresh "Onboard Service" attempt. Fixed with
one shared `closeDialog()` wired to both `Dialog`'s `onClose` and the
Cancel button (previously two separate, inconsistent handlers).

**Not fixed, named instead:** `InventoryService.update` still has no
duplicate-name check the way `create()` does (`existsByName` is only
called from `create`) — renaming a service via `PUT` to collide with an
existing name would surface as a raw constraint-violation error, not a
clean `409`. Out of this task's literal scope ("double-check field
application," not "add missing validation"), and pre-existing, not
introduced here — added to the `docs/SPECS.md` §9.2 backlog alongside
this codebase's other named-but-deferred gaps.

**Verified live:** `PUT`-edited a real registered service's
`managementUrl` via the API, confirmed the change persisted and discovery
re-ran (status briefly `PENDING`, then `ACTIVE` again). Confirmed
`schema/fetch` called twice against an already-`hasSchema: true` entry
both times returns `200`.

---

## Alternatives Considered

### On-demand schema re-fetch per Admin Console page load, instead of caching `status` (rejected)

- **Pros:** Always current, no stale-status window.
- **Cons:** Turns viewing the registry into a live dependency on every
  registered service's availability — the exact anti-pattern ADR-014
  already rejected for the identity cache, for the same Zero Trust
  reliability reasoning.
- **Verdict:** Rejected — `status` is a cached, periodically-refreshed
  field by design, same as `idp_identities`.

---

## Self-Criticism

| Risk | Severity | Mitigation |
|---|---|---|
| `AutoDiscoveryWorker`'s MCP `tools/list` probe assumes a stateless `POST {base_url}/message` call — an agent that strictly requires the `GET /sse` session handshake even for discovery will always land in `WARNING`, not because it's actually broken | Medium | Named explicitly as an assumption, not a spec fact (see Decision). No MCP agent in this repo's own fleet (Agent A/B via `hubspot-mcp`) is registered through this new onboarding flow yet, so it's untested against a real stateful-only agent. |
| `WARNING` has no UI-driven way to clear other than deleting and re-onboarding the service | Low | Deliberate MVP scope — a "Retry Discovery" action is a natural, low-effort extension (Future Migration Path), not built because the task's own Task 5 UI list didn't ask for it. |
| `AutoDiscoveryWorker`/`HealthPollingService`'s actual HTTP-calling code (the `WebClient` probes) has no dedicated mocked-`WebClient` unit test | Low | Consistent with this codebase's established precedent (`KeycloakIdpAdapter`, `McpBackendClient` — never unit-tested with mocked HTTP) — proven instead by `InventoryRegistryIT` against a real WireMock target. The one pure, extractable piece of decision logic (`HealthPollingService.statusTransition`) does have a direct unit test, same as `KeycloakIdpAdapter#isSystemClient`'s precedent. |
| A service `name` collision between the inventory registry and `RequestTargetResolver`'s path-segment extraction is required for passive `last_successful_call` tracking to work at all — an inventory entry named anything other than the exact path segment (`"service-a"`, not `"Service A"` or `"svc-a"`) silently never receives telemetry | Medium | Named explicitly, not hidden — `HealthMetricRepository.upsertSuccessfulCallByServiceName`'s Javadoc states the no-op-on-mismatch behavior. No validation enforces the naming convention at onboarding time; an operator registering `service-a` under a different display name gets a registry entry that's otherwise fully functional (discovery, health polling) but never shows passive traffic data. |
| Like every other `idp_identities`/sync-based cache in this codebase, `inventory_services`/`health_metrics` accumulate no reconciliation — a deleted service is only removed by an explicit `DELETE`, never automatically | Low | Consistent with this repo's established posture (identity sync has the same property, named in its own ADR) — not a new gap. |
| ~~`AutoDiscoveryWorker`/`HealthPollingService` use a plain `WebClient` with no ZTE mTLS client certificate~~ | — | **Superseded, 2026-08-11** — investigated and found factually incorrect (see the mTLS/OpenAPI Amendment above): both already inherit the gateway's mTLS connector via Spring Boot's default `WebClient.Builder` wiring. The real, separate gap this row was reacting to (`service-a`/`service-b` lacking `/v3/api-docs`) was fixed the same day; the health-polling port mismatch it also touched on was fixed by the `management_url` amendment. Left struck through rather than deleted, so the correction is traceable. |
| No size limit on the captured `discovered_schema` payload — a misbehaving or malicious target could return an enormous `/v3/api-docs`/`tools/list` body, stored verbatim | Low | Not enforced — `zte.inventory.discovery-timeout-ms` bounds how long a probe can run, but not response size. Onboarding is an operator (`ADMIN`-only) action against a URL the operator themselves typed in, not an untrusted/public input path, so this is a lower-severity gap than it would be on a public-facing endpoint; a `Content-Length`/streaming cap is a reasonable future hardening item (§9.2) if the registry is ever opened to less-trusted onboarding. |
| Adding `swagger-ui-react` roughly tripled the Admin Console's built bundle (589 KB → 1.88 MB raw, 177 KB → 538 KB gzipped) | Low | Direct, known cost of the task's specified library choice — not mitigated here (no dynamic import/code-splitting), since that wasn't asked for; a natural follow-up if bundle size becomes a real problem (§9.2). |
| `swagger-ui-react`'s transitive `react-inspector@6.0.2` declares a React 16-18 peer range, not this project's React 19 | Low | `npm install` resolved it anyway (a warning, not a hard failure); `tsc -b` and `vite build` both succeed cleanly and no runtime issue was observed in manual testing. Worth revisiting only if `swagger-ui-react` itself is upgraded and starts failing outright. |
| `docs_url` is fully operator-trusted — no validation that it points at the same host/service being registered (an `ADMIN` could set `docs_url` on one entry to a completely unrelated internal endpoint, and `AutoDiscoveryWorker` will fetch and store whatever it returns) | Low | Same trust boundary as `base_url`/`management_url` already have — this is an `ADMIN`-only onboarding action, not attacker-reachable input; the gateway's own mTLS connector is still used for the request regardless of target, so this doesn't bypass any existing trust boundary, just extends operator-controlled reach to one more URL field. |
| `hasSchema` is binary — it can't distinguish "never attempted," "target unreachable," and "reached but returned nothing valid" | Low | The Admin Console's `status`/`actuatorStatus` columns already carry reachability signal; `hasSchema` only ever needed to answer one question ("is 'View Schema' safe to click"), not diagnose why not — adding more states wasn't asked for and isn't needed for that one job. |

---

## Consequences

- **Positive:** Operators get one place to see every registered REST
  service / MCP agent, whether it passed its initial connectivity check,
  and whether real traffic has actually reached it recently.
- **Positive:** Onboarding a broken or unreachable service is now visible
  immediately (`WARNING`) rather than silently failing the first time a
  real request tries to route to it.
- **Positive:** The health-poll job's `ACTIVE`↔`DOWN` self-healing means an
  operator doesn't need to manually flip a service back to `ACTIVE` after
  a transient outage recovers.
- **Positive:** `HealthTelemetryService` is a second, independent proof
  that the `Sinks.Many`+`boundedElastic` fire-and-forget pattern
  (`RequestLogAuditService`, ADR-013) generalizes cleanly to a new async
  write concern.
- **Negative:** The MCP discovery probe's stateless-call assumption is
  unverified against a real stateful-only MCP agent (see Self-Critique) —
  a real risk if `hubspot-mcp` (or a future agent) is ever onboarded
  through this flow and turns out to require the session handshake.
- **Negative:** Passive telemetry silently depends on exact name matching
  between the registry and `RequestTargetResolver`'s path-derived service
  name — an easy, undetected misconfiguration (see Self-Critique).

---

## Future Migration Path

- **A "Retry Discovery" Admin Console action**, to clear a stuck `WARNING`
  without deleting and re-onboarding the service.
- **A history table for `health_metrics`** (ping latency over time, not
  just the latest value), if operators need trend visibility rather than
  just current state.
- **Validate the MCP stateless-discovery assumption** against a real
  session-only agent, and fall back to a full `GET /sse` handshake for
  discovery if needed.
- **Reconciliation for stale inventory rows**, mirroring the same backlog
  item already named for `idp_identities`/`idp_identity_relations`.
- **Enforce (or at least warn on) name mismatches** between a registered
  service and any `GatewayRouteConfig` route it's meant to represent, so
  the passive-telemetry naming constraint (see Self-Critique) isn't a
  silent trap.
- **A dedicated `src/test` source set for `service-a`/`service-b`**, e.g.
  asserting `/v3/api-docs` returns 200 — neither module has ever had one
  (all existing coverage is external, via `gateway-service`'s own
  `HappyPathIT`/`ZeroTrustBreachIT` against WireMock stand-ins); adding one
  now for a single assertion would be a disproportionate new-infrastructure
  investment, so this amendment's OpenAPI claim was verified live instead
  (see the Amendment section above).
