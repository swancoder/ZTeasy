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
# Note: state is ephemeral by design (ADR-027) — Postgres and Keycloak keep
# their data inside the container, so a stop/start cycle wipes the audit
# trail, the approval queue and any Keycloak session, and re-imports the
# realm. That's the demo's accepted tradeoff, not a bug in this script.
set -euo pipefail

AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"

# Dependency order for start; stop walks it backwards.
APPS=(postgres keycloak service-b service-a mcp-bridge zt-agents gateway)

latest_revision() {
  $AZ containerapp revision list -n "$1" -g "$RG" \
      --query 'sort_by([],&properties.createdTime)[-1].name' -o tsv 2>/dev/null
}

case "${1:-status}" in

  stop)
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
    FQDN=$($AZ containerapp show -n gateway -g "$RG" --query properties.configuration.ingress.fqdn -o tsv 2>/dev/null)
    echo
    echo "Give it a minute, then:"
    echo "  Admin Console:   https://${FQDN}:8080/admin/index.html"
    echo "  Approval Center: https://${FQDN}:8080/approver/index.html"
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
