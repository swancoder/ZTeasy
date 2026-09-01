# ADR-033 — Demo durability, and the configuration that only exists in the cloud

**Status:** Accepted · 2026-09-01
**Context:** Stage 33 · supersedes parts of ADR-027's "state is ephemeral by design"

## Context

A sweep of the live deployment before a demo turned up four defects that share
one property: **none of them can be observed on a developer machine.** Each was
found by exercising the deployed system, not by reading the code.

1. **Postgres had no storage at all** (`volumes: null`). Every stop/start —
   including the nightly `power.sh stop` that exists precisely to save money —
   returned the demo to a factory state: no audit trail, no approval queue, no
   policy toggles, no ACAP lifecycle history.
2. **Token metering had never worked anywhere.** `GatewayWebClients` has
   honoured `zte.gateway.ca-cert` since ADR-029, but *nothing ever set it* — not
   `application.yml`, not the compose file, not `deploy.sh`. Every call from
   zt-agents to the gateway failed TLS against the JVM's public-CA trust, and
   because metering is fire-and-forget the failure surfaced only as a `WARN` no
   one read. Consequence: `llm_usage` was empty, so the executive dashboard's
   money panels — the numbers a CFO looks at first — showed zeros.
3. **The gateway did not survive a Postgres restart.** The R2DBC pool handed out
   sockets to a server that no longer existed; the Admin API returned 500 until
   the gateway itself was restarted. In a deployment whose whole environment is
   stopped and started nightly, in arbitrary order, that is a daily failure.
4. **The policy document was baked into the image.** The console's "Reload
   Policies" button re-read a file no operator could change, which made the AI
   auditor's "Modify policy" suggestion (ADR-031) a dead end in the cloud: you
   could copy the suggested YAML and then had nowhere to put it.

## Decision

### Durability: dump and restore, not a data volume

Postgres cannot run its data directory on SMB Azure Files. Measured, not
assumed — mounting the share and starting the container gives:

```
chmod: /var/lib/postgresql/data/pgdata: Operation not permitted
initdb: error: could not change permissions of directory ... Operation not permitted
```
followed by `CrashLoopBackOff`. Azure Files *NFS* would work (real POSIX
permissions) but requires a Premium FileStorage account — a ~$16/month floor
for a demo whose entire point is that it costs nothing when switched off.
Azure Database for PostgreSQL is likewise a paid resource.

So: keep the data directory ephemeral and move the *durability* to a dump.

- A `db-backup` Container Apps job runs `pg_dump` onto the `pgbackup` share.
- The postgres app mounts the same share **read-only** at
  `/docker-entrypoint-initdb.d`. The official image runs any `*.sql` it finds
  there whenever it initialises an empty data directory — which, with ephemeral
  storage, is every single start. **The restore path is therefore not code we
  wrote and cannot rot.**
- `power.sh stop` runs the backup first and *aborts the stop* if it fails,
  because a stop that silently loses the day's state is worse than no stop.

The dump is written to `10-restore.sql.tmp` and renamed. The restore directory
and the backup target are the same share, so a half-written file must never be
visible under the name the next start will execute.

What survives: everything in the database — audit trail, approvals, policy
activation, ACAP lifecycle and re-authorisation history, LLM usage. What does
not: anything written after the last dump. This is a demo, and the loss window
is bounded by an operator action (the nightly stop) rather than by luck.

### Self-healing database connections

`spring.r2dbc.pool` now validates on borrow (`SELECT 1`, `validation-depth:
REMOTE`) with a bounded `max-life-time`. A restarted database costs one
discarded connection instead of a dead gateway.

### Configuration that the cloud needs and localhost does not

- `zte.gateway.ca-cert` is now a real property (`ZTE_GATEWAY_CA_CERT`), the
  certs share is mounted into zt-agents, and both `deploy.sh` and
  `docker-compose.cloud.yml` set it.
- `ZTE_POLICY_FILE` points at the policy document on the certs share, uploaded
  by `deploy.sh` alongside the ACAP profiles. "Reload Policies" now re-reads a
  file an operator can actually replace.
- `deploy/azure/attach-volume.sh` generalises the raw-ARM volume attachment
  that previously existed only for the agent-runner job.

## Consequences

- The demo survives the nightly stop. Verified end to end: 14 request logs, 1
  policy toggle, 1 approval, 3 audit runs and 1 metered LLM call, dumped,
  the data directory wiped by a restart, and all five counts identical
  afterwards — with the gateways serving 200s throughout, never restarted.
- The executive dashboard shows real money (first live figure: 42,363 micros
  for one audit run) instead of zeros.
- Applying an audit finding is a demonstrable loop: replace the file on the
  share, click Reload Policies, watch the rule count go 27 → 28.
- `power.sh stop` is slower by the length of a `pg_dump`, and refuses to
  proceed if the backup fails.

## Self-critique

- **The restore is only as good as the last dump.** A crash between dumps loses
  the delta silently. A scheduled (cron-trigger) backup job would narrow the
  window; it is not wired up, because the failure that actually happens here is
  the planned stop, not the crash.
- **`pg_dump` while the system is live** is a consistent snapshot of the
  database, not of the system — an approval decided mid-dump may or may not be
  in it. Acceptable at demo scale; not a backup strategy for anything real.
- **Trading crash-durability for cost is a choice, and it can be revisited in
  one step:** a Premium FileStorage account with an NFS share turns this into a
  genuine data volume without touching the application.
- **Validation-on-borrow costs a round trip per checkout.** At demo traffic
  this is invisible; a busier deployment would want `max-idle-time` tuning
  instead of validating every borrow.
- A restored dump carries its own `flyway_schema_history`, so a *newer* image
  starting against an *older* dump migrates forward correctly — but the reverse
  (old image, newer dump) is not defended against at all.

## Postscript: how one name resolved two ways

Metering failed with a 30-second connect timeout to
`gateway.internal.<env>.northeurope.azurecontainerapps.io/100.100.0.210`, while
`nc` from *the same container* found `gateway:8080` open at `100.100.230.154`.
Inside the environment the two names are not aliases:

| name | resolves to | reachable |
|---|---|---|
| `gateway` → `gateway.k8se-apps.svc.cluster.local` | 100.100.230.154 | yes |
| `gateway.internal.<env>.azurecontainerapps.io` | 100.100.0.210 | no (TCP ingress) |

ADR-027 already recorded "use bare app names for TCP apps". The live zt-agents
app nevertheless held the FQDN in `GATEWAY_INTERNAL_URI` — set by hand in an
early session, before that lesson, and never revisited: `deploy.sh` only
applies its value when it *creates* an app, so a corrected script silently fails
to correct anything that already exists.

**The general defect is configuration drift between the deploy script and the
running system, and it is invisible to both code review and a fresh deploy of
the script.** Worth an explicit check — compare the live env of each app against
what `deploy.sh` would set — before any demo that matters.
