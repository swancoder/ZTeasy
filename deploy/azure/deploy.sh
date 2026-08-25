#!/usr/bin/env bash
# ============================================================
# ZTeasy — Azure Container Apps provisioning (ADR-027)
# ============================================================
# Idempotent-ish runbook: safe to re-run; az create calls either succeed or
# no-op/update. Two-phase by design: the gateway FQDN only exists after the
# gateway app is created, so certs + the Keycloak realm are (re)generated
# against the real origin in phase 2 (see docs/azure-deployment-plan.md).
#
# Required env:
#   GHCR_USER, GHCR_PAT       — registry pulls (read:packages)
#   HUBSPOT_TOKEN             — MCP bridge secret
# Optional env:
#   ANTHROPIC_API_KEY         — zt-agents (skipped if empty)
#   AZ, RG, LOC, ENV_NAME, TAG — overrides below
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

AZ_BIN="${AZ:-az}"
# Retry wrapper: transient local DNS failures were seen killing long-running
# az polls mid-deploy; every az call goes through this. Exported so the
# create-app-with-certs.sh / attach-certs-to-job.sh children use it too.
azr() {
  local n=0
  until "$AZ_BIN" "$@"; do
    n=$((n + 1)); [ "$n" -ge 3 ] && return 1
    echo "azr: retry $n after failure: az $1 ${2:-}" >&2; sleep 10
  done
}
export AZ_BIN
export -f azr
AZ=azr
export AZ
RG="${RG:-zteasy-demo-rg}"
# westeurope rejected this subscription ("not accepting new customers");
# northeurope verified working 2026-08-25.
LOC="${LOC:-northeurope}"
# The create-app-with-certs.sh / attach-certs-to-job.sh children read these.
# SECRET_* carries the value behind an env written as "secretref:<name>".
export RG ENV_NAME LOC GHCR_USER GHCR_PAT
# -v2: the first environment was created without a custom VNET, but external
# TCP ingress (the gateway's mTLS-preserving entry, ADR-027) is only allowed
# on VNET-backed environments — discovered live; a VNET can't be added to an
# existing environment.
ENV_NAME="${ENV_NAME:-zteasy-env-v2}"
VNET="${VNET:-zteasy-vnet}"
STORAGE="${STORAGE:-zteasycerts$RANDOM}"
REGISTRY="ghcr.io"
IMG="${IMG:-ghcr.io/${GHCR_USER:?set GHCR_USER}}"
TAG="${TAG:-azure-1}"
OBO_SECRET="${ZTE_OBO_SECRET:-zte-obo-dev-secret-change-in-production}"
# Shared secret for /api/v1/internal/** (ADR-027 amendment): with a public
# ingress those endpoints would otherwise serve the policy document — and
# accept reload — to anyone. Random per deployment unless pinned.
INTERNAL_KEY="${ZTE_INTERNAL_API_KEY:-$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')}"
# Keycloak's own admin account: never leave it at admin/admin on a
# deployment whose gateway is public.
KC_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')}"
export SECRET_INTERNAL_API_KEY="$INTERNAL_KEY"

echo "── resource group + environment ──"
$AZ config set extension.use_dynamic_install=yes_without_prompt -o none 2>/dev/null || true
$AZ extension add --name containerapp -o none 2>/dev/null || true
# Fresh subscriptions have no resource providers registered
for provider in Microsoft.App Microsoft.OperationalInsights Microsoft.Storage Microsoft.Network; do
  $AZ provider register -n "$provider" --wait -o none
done
# RG location is metadata only — keep an existing group wherever it was made
$AZ group show -n "$RG" -o none 2>/dev/null || $AZ group create -n "$RG" -l "$LOC" -o none
# Custom VNET — required for the gateway's external TCP ingress (see above)
if ! $AZ network vnet show -g "$RG" -n "$VNET" -o none 2>/dev/null; then
  $AZ network vnet create -g "$RG" -n "$VNET" -l "$LOC" \
      --address-prefix 10.0.0.0/16 \
      --subnet-name aca-infra --subnet-prefixes 10.0.0.0/23 -o none
fi
# A workload-profiles environment requires its infrastructure subnet to be
# delegated to Microsoft.App/environments (enforced live, 2026-08-25)
$AZ network vnet subnet update -g "$RG" --vnet-name "$VNET" -n aca-infra \
    --delegations Microsoft.App/environments -o none
SUBNET_ID=$($AZ network vnet subnet show -g "$RG" --vnet-name "$VNET" -n aca-infra --query id -o tsv)
if ! $AZ containerapp env show -n "$ENV_NAME" -g "$RG" -o none 2>/dev/null; then
  $AZ containerapp env create -n "$ENV_NAME" -g "$RG" -l "$LOC" \
      --infrastructure-subnet-resource-id "$SUBNET_ID" -o none
fi
DOMAIN=$($AZ containerapp env show -n "$ENV_NAME" -g "$RG" --query properties.defaultDomain -o tsv)
echo "environment domain: $DOMAIN"
# Intra-environment addressing uses the app's BARE NAME (postgres:5432,
# keycloak:8080, …), not the `<app>.internal.<domain>` FQDN — the FQDN form
# times out for TCP-transport apps (verified live 2026-08-25: the gateway's
# Flyway connect hung until DB_HOST was switched to the bare name).

echo "── certs file share ──"
EXISTING_STORAGE=$($AZ storage account list -g "$RG" --query "[?starts_with(name,'zteasycerts')].name | [0]" -o tsv)
if [ -n "$EXISTING_STORAGE" ]; then STORAGE="$EXISTING_STORAGE"; else
  $AZ storage account create -n "$STORAGE" -g "$RG" -l "$LOC" --sku Standard_LRS -o none
fi
STKEY=$($AZ storage account keys list -n "$STORAGE" -g "$RG" --query "[0].value" -o tsv)
$AZ storage share-rm create --storage-account "$STORAGE" -g "$RG" -n certs -o none 2>/dev/null || true
$AZ containerapp env storage set -n "$ENV_NAME" -g "$RG" --storage-name certs \
    --azure-file-account-name "$STORAGE" --azure-file-account-key "$STKEY" \
    --azure-file-share-name certs --access-mode ReadOnly -o none

upload_certs() {
  echo "── uploading certs ──"
  $AZ storage file upload-batch --account-name "$STORAGE" --account-key "$STKEY" \
      --destination certs --source certs --pattern '*' --no-progress -o none
}
upload_certs

REG_ARGS=(--registry-server "$REGISTRY" --registry-username "$GHCR_USER" --registry-password "${GHCR_PAT:?set GHCR_PAT}")

create_tcp_app() {   # name image port [extra args...]
  local name=$1 image=$2 port=$3; shift 3
  $AZ containerapp create -n "$name" -g "$RG" --environment "$ENV_NAME" \
      --image "$image" --ingress internal --transport tcp \
      --target-port "$port" --exposed-port "$port" \
      --min-replicas 1 --max-replicas 1 --cpu 0.5 --memory 1Gi \
      "${REG_ARGS[@]}" "$@" -o none
}

echo "── postgres ──"
$AZ containerapp create -n postgres -g "$RG" --environment "$ENV_NAME" \
    --image postgres:16-alpine --ingress internal --transport tcp \
    --target-port 5432 --exposed-port 5432 --min-replicas 1 --max-replicas 1 \
    --cpu 0.5 --memory 1Gi \
    --env-vars POSTGRES_DB=zte_db POSTGRES_USER=zte_user POSTGRES_PASSWORD=zte_pass -o none

echo "── keycloak (phase-1: placeholder origin ok, updated in phase 2) ──"
# The phase-1 keycloak image must exist in the registry BEFORE the app
# create pulls it (phase 2 rebuilds it against the real origin anyway).
if ! docker manifest inspect "$IMG/zteasy-keycloak:$TAG" >/dev/null 2>&1; then
  python3 deploy/azure/make-cloud-realm.py "https://placeholder.invalid"
  docker build -f deploy/azure/Dockerfile.keycloak -t "$IMG/zteasy-keycloak:$TAG" .
  docker push "$IMG/zteasy-keycloak:$TAG"
fi
create_tcp_app keycloak "$IMG/zteasy-keycloak:$TAG" 8080 \
    --secrets "kc-admin-password=$KC_ADMIN_PASSWORD" \
    --env-vars KC_DB=dev-file KEYCLOAK_ADMIN=admin \
      "KEYCLOAK_ADMIN_PASSWORD=secretref:kc-admin-password" \
      KC_HTTP_PORT=8080 KC_HTTP_RELATIVE_PATH=/auth KC_HOSTNAME_STRICT=false \
      KC_HEALTH_ENABLED=true KC_PROXY=edge \
      "KC_HOSTNAME_URL=https://placeholder.invalid/auth"

echo "── service-b / service-a (mTLS, certs volume via YAML) ──"
# MANAGEMENT_PORT == the service's own API port on purpose: a Container App
# publishes exactly one port, so the separate plain-HTTP management ports
# (9081/9082) are unreachable here and the gateway's health poll would flip
# both services to DOWN — which also drops their inventory-driven routes.
# On the shared port, Spring serves /actuator/health from the main context,
# and the poll reaches it over mTLS like every other gateway call.
bash deploy/azure/create-app-with-certs.sh service-b "$IMG/zteasy-service-b:$TAG" 8082 \
    "ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET MANAGEMENT_PORT=8082"
bash deploy/azure/create-app-with-certs.sh service-a "$IMG/zteasy-service-a:$TAG" 8081 \
    "ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET SERVICE_B_URI=https://service-b:8082 MANAGEMENT_PORT=8081"

echo "── mcp bridge ──"
$AZ containerapp create -n mcp-bridge -g "$RG" --environment "$ENV_NAME" \
    --image "$IMG/hubspot-mcp-bridge:$TAG" --ingress internal --transport tcp \
    --target-port 9090 --exposed-port 9090 --min-replicas 1 --max-replicas 1 \
    --cpu 0.25 --memory 0.5Gi "${REG_ARGS[@]}" \
    --secrets "hubspot-token=${HUBSPOT_TOKEN:?set HUBSPOT_TOKEN}" \
    --env-vars "HUBSPOT_TOKEN=secretref:hubspot-token" -o none

if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  echo "── zt-agents ──"
  create_tcp_app zt-agents "$IMG/zteasy-zt-agents:$TAG" 8083 \
      --secrets "anthropic-key=$ANTHROPIC_API_KEY" "internal-api-key=$INTERNAL_KEY" \
      --env-vars "ANTHROPIC_API_KEY=secretref:anthropic-key" \
        "ZTE_INTERNAL_API_KEY=secretref:internal-api-key" \
        "GATEWAY_INTERNAL_URI=https://gateway:8080"
fi

echo "── gateway (phase-1 create → learn FQDN) ──"
bash deploy/azure/create-app-with-certs.sh gateway "$IMG/zteasy-gateway:$TAG" 8080 \
    "DB_HOST=postgres DB_PORT=5432 DB_NAME=zte_db DB_USER=zte_user DB_PASSWORD=zte_pass \
     KEYCLOAK_JWKS_URI=http://keycloak:8080/auth/realms/zte-realm/protocol/openid-connect/certs \
     ZTE_AUTH_PROXY_ENABLED=true ZTE_AUTH_PROXY_URI=http://keycloak:8080 \
     ZTE_IDP_KEYCLOAK_BASE_URI=http://keycloak:8080/auth \
     SERVICE_A_URI=https://service-a:8081 SERVICE_B_URI=https://service-b:8082 \
     MCP_BACKEND_URI=http://mcp-bridge:9090 \
     ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET \
     ZTE_INTERNAL_API_KEY=secretref:internal-api-key \
     KEYCLOAK_ISSUER_URI=https://placeholder.invalid/auth/realms/zte-realm \
     ZTE_UI_OIDC_AUTHORITY=https://placeholder.invalid/auth/realms/zte-realm" \
    external

GW_FQDN=$($AZ containerapp show -n gateway -g "$RG" --query properties.configuration.ingress.fqdn -o tsv)
ORIGIN="https://${GW_FQDN}:8080"
echo "gateway origin: $ORIGIN"

echo "── phase 2: real origin everywhere ──"
GATEWAY_EXTRA_SANS="DNS:${GW_FQDN}" ./certs/generate-certs.sh
upload_certs
python3 deploy/azure/make-cloud-realm.py "$ORIGIN"
docker build -f deploy/azure/Dockerfile.keycloak -t "$IMG/zteasy-keycloak:$TAG" .
docker push "$IMG/zteasy-keycloak:$TAG"
$AZ containerapp update -n keycloak -g "$RG" \
    --set-env-vars "KC_HOSTNAME_URL=$ORIGIN/auth" -o none
$AZ containerapp update -n gateway -g "$RG" \
    --set-env-vars "KEYCLOAK_ISSUER_URI=$ORIGIN/auth/realms/zte-realm" \
      "ZTE_UI_OIDC_AUTHORITY=$ORIGIN/auth/realms/zte-realm" -o none
# Every cert-holding app restarts: the phase-2 run above reissues the
# gateway's server cert (now with the FQDN SAN) and, since certs are shared
# files, refreshes the others too. The CA itself is reused across runs, so a
# re-deploy no longer invalidates certs held by anything that isn't
# restarted here — set ZTE_REGENERATE_CA=1 only when you intend a full PKI
# swap. Keycloak restarts to re-import the origin-correct realm.
for app in keycloak gateway service-a service-b; do
  REV=$($AZ containerapp revision list -n "$app" -g "$RG" \
        --query 'sort_by([],&properties.createdTime)[-1].name' -o tsv)
  $AZ containerapp revision restart -n "$app" -g "$RG" --revision "$REV" -o none || true
done

echo "── agent-runner job (manual trigger) ──"
$AZ containerapp job create -n agent-runner -g "$RG" --environment "$ENV_NAME" \
    --trigger-type Manual --replica-timeout 600 --replica-retry-limit 0 \
    --image "$IMG/hubspot-mcp-agents:$TAG" --cpu 0.25 --memory 0.5Gi \
    "${REG_ARGS[@]}" \
    --env-vars "KEYCLOAK_TOKEN_URL=http://keycloak:8080/auth/realms/zte-realm/protocol/openid-connect/token" \
      "GATEWAY_URL=https://gateway:8080" \
      GATEWAY_CLIENT_CERT=/app/certs/client.pem \
      AGENT_A_CLIENT_ID=agent-a AGENT_A_CLIENT_SECRET=agent-a-secret-dev-only \
      AGENT_B_CLIENT_ID=agent-b AGENT_B_CLIENT_SECRET=agent-b-secret-dev-only \
      AGENT_CRM_CLIENT_ID=crm-account-health-emea-01 \
      AGENT_CRM_CLIENT_SECRET=crm-account-health-emea-01-secret-dev-only \
    -o none 2>/dev/null || echo "(job exists — leaving as is)"
# attach the certs volume to the job (client.pem) — YAML-only operation
bash deploy/azure/attach-certs-to-job.sh agent-runner

echo "── register the MCP bridge in the APIM inventory ──"
# InventoryBootstrapSeeder only seeds service-a/service-b, so the bridge
# would otherwise be missing from the Registry tab entirely (its MCP proxy
# traffic works regardless — mcp-backend.uri is separate config — but the
# operator sees no entry and no tool schema). Idempotent: a repeat POST
# returns 409, which we treat as success.
for attempt in $(seq 1 10); do
  ADMIN_TOKEN=$(curl -sk -m 30 -X POST \
      "$ORIGIN/auth/realms/zte-realm/protocol/openid-connect/token" \
      -d "grant_type=password&client_id=zte-gateway&client_secret=zte-gateway-secret" \
      -d "username=zte-admin&password=${ZTE_ADMIN_PASSWORD:-Admin@123!}" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null || true)
  [ -n "$ADMIN_TOKEN" ] && break
  sleep 20
done
if [ -n "$ADMIN_TOKEN" ]; then
  CODE=$(curl -sk -m 60 -o /dev/null -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
      "$ORIGIN/api/v1/admin/inventory" \
      -d '{"name":"hubspot-mcp","targetType":"MCP","baseUrl":"http://mcp-bridge:9090"}')
  echo "inventory onboard hubspot-mcp -> $CODE"
else
  echo "WARN: could not obtain an admin token — onboard hubspot-mcp manually via the Registry tab" >&2
fi

echo
echo "══════════════════════════════════════════════════════"
echo " Admin Console:    $ORIGIN/admin/index.html   (zte-admin / Admin@123!)"
echo " Approval Center:  $ORIGIN/approver/index.html (zte-test-user / User@123!)"
echo " Demo run:         az containerapp job start -n agent-runner -g $RG"
echo "══════════════════════════════════════════════════════"
