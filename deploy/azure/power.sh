#!/usr/bin/env bash
# ============================================================
# ZTeasy — stop / start the whole Azure deployment (ADR-027)
# ============================================================
# Container Apps has no `stop` verb: an app is "off" when its active
# revision is deactivated, which scales it to zero replicas and stops
# billing for compute (the environment, storage account and public FQDN
# survive, so starting again needs no redeploy and the URLs don't change).
#
# Usage:
#   ./deploy/azure/power.sh stop      # deactivate everything (overnight)
#   ./deploy/azure/power.sh start     # bring it all back, in dependency order
#   ./deploy/azure/power.sh status    # what's running right now
#
# Env: AZ (az binary), RG (default zteasy-demo-rg)
#
# State: Postgres keeps its data inside the container (SMB Azure Files cannot
# host a Postgres data directory — ADR-033), so `stop` first runs the
# `db-backup` job, which dumps the database onto the pgbackup share. The
# postgres app mounts that share read-only at /docker-entrypoint-initdb.d, so
# `start` restores it automatically while initialising the empty data
# directory. What survives is everything in the database: audit trail,
# approval queue, policy toggles, ACAP lifecycle and re-authorisations.
# What does not: anything written after the last dump, and Keycloak sessions
# (its realm is re-imported on every start, by design).
set -euo pipefail

AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"

# Dependency order for start; stop walks it backwards.
# chat sits with the other downstream services: it must be up before the
# gateways route to it, and it must be stopped with everything else — an app
# left out of this list keeps billing all night while looking parked (ADR-039).
# ADR-040 merged the two front doors into one: `gateway-web` serves browsers AND
# agents, and the old TCP-passthrough `gateway` app is deactivated (kept only so
# the split can be restored quickly). Leaving it in this list would wake it up
# every morning to do nothing but bill.
APPS=(postgres keycloak service-b service-a chat mcp-bridge zt-agents gateway-web)

latest_revision() {
  $AZ containerapp revision list -n "$1" -g "$RG" \
      --query 'sort_by([],&properties.createdTime)[-1].name' -o tsv 2>/dev/null
}

case "${1:-status}" in

  stop)
    # Dump the database before anything is taken down — the whole point of the
    # nightly stop is that tomorrow's demo starts where today's ended.
    if $AZ containerapp job show -n db-backup -g "$RG" -o none 2>/dev/null; then
      echo "── backing up the database ──"
      EXEC=$($AZ containerapp job start -n db-backup -g "$RG" --query name -o tsv)
      for _ in $(seq 1 30); do
        ST=$($AZ containerapp job execution show -n db-backup -g "$RG" \
             --job-execution-name "$EXEC" --query properties.status -o tsv 2>/dev/null || echo Unknown)
        case "$ST" in
          Succeeded) echo "backup ok ($EXEC)"; break ;;
          Failed|Degraded)
            echo "BACKUP FAILED ($EXEC) — stopping anyway would lose today's state." >&2
            echo "Investigate with: $AZ containerapp job logs show -n db-backup -g $RG --container db-backup" >&2
            exit 1 ;;
          *) sleep 10 ;;
        esac
      done
    else
      echo "WARNING: no db-backup job — this stop will lose the database." >&2
    fi

    # Reverse order: take the front door down first so nothing is served by
    # a gateway whose dependencies are already gone.
    for (( i=${#APPS[@]}-1; i>=0; i-- )); do
      app="${APPS[$i]}"
      rev=$(latest_revision "$app") || true
      [ -z "$rev" ] && { echo "$app: no revision, skipping"; continue; }
      $AZ containerapp revision deactivate -n "$app" -g "$RG" --revision "$rev" -o none 2>/dev/null \
        && echo "$app: stopped ($rev)" \
        || echo "$app: already stopped"
    done
    echo
    echo "All apps deactivated. Compute billing stops; FQDN and data volumes are kept."
    echo "Start again with: $0 start"
    ;;

  start)
    for app in "${APPS[@]}"; do
      rev=$(latest_revision "$app") || true
      [ -z "$rev" ] && { echo "$app: no revision, skipping"; continue; }
      $AZ containerapp revision activate -n "$app" -g "$RG" --revision "$rev" -o none 2>/dev/null \
        && echo "$app: started ($rev)" \
        || echo "$app: already running"
      # Postgres and Keycloak must actually be up before the apps that
      # connect to them at startup (Flyway, JWKS) — those fail fast otherwise.
      case "$app" in postgres|keycloak) sleep 30 ;; esac
    done
    echo
    echo "Postgres restored from the last dump on the pgbackup share (if one exists)."
    # The browser-facing app's custom domain (ADR-028), falling back to its
    # Azure FQDN if no domain is bound.
    WEB=$($AZ containerapp show -n gateway-web -g "$RG" \
          --query 'properties.configuration.ingress.customDomains[0].name' -o tsv 2>/dev/null)
    [ -z "$WEB" ] && WEB=$($AZ containerapp show -n gateway-web -g "$RG" \
          --query properties.configuration.ingress.fqdn -o tsv 2>/dev/null)
    echo
    echo "Give it a minute, then:"
    echo "  Admin Console:   https://${WEB}/admin/index.html"
    echo "  Approval Center: https://${WEB}/approver/index.html"
    ;;

  status)
    printf "%-12s %-26s %-10s %s\n" APP REVISION ACTIVE STATE
    for app in "${APPS[@]}"; do
      # -o tsv puts each projected field on its own LINE here (not tab-joined),
      # so read the three values as an array rather than with `read a b c`.
      mapfile -t fields < <($AZ containerapp revision list -n "$app" -g "$RG" \
          --query 'sort_by([],&properties.createdTime)[-1].[name,properties.active,properties.runningState]' \
          -o tsv 2>/dev/null)
      printf "%-12s %-26s %-10s %s\n" "$app" "${fields[0]:-—}" "${fields[1]:-—}" "${fields[2]:-—}"
    done
    ;;

  *)
    echo "usage: $0 {stop|start|status}" >&2
    exit 1
    ;;
esac
