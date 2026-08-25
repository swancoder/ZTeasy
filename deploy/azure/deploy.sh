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

AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"
LOC="${LOC:-westeurope}"
ENV_NAME="${ENV_NAME:-zteasy-env}"
STORAGE="${STORAGE:-zteasycerts$RANDOM}"
REGISTRY="ghcr.io"
IMG="${IMG:-ghcr.io/${GHCR_USER:?set GHCR_USER}}"
TAG="${TAG:-azure-1}"
OBO_SECRET="${ZTE_OBO_SECRET:-zte-obo-dev-secret-change-in-production}"

echo "── resource group + environment ──"
$AZ group create -n "$RG" -l "$LOC" -o none
$AZ containerapp env create -n "$ENV_NAME" -g "$RG" -l "$LOC" -o none 2>/dev/null || true
DOMAIN=$($AZ containerapp env show -n "$ENV_NAME" -g "$RG" --query properties.defaultDomain -o tsv)
INTERNAL="internal.${DOMAIN}"
echo "environment domain: $DOMAIN"

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
create_tcp_app keycloak "$IMG/zteasy-keycloak:$TAG" 8080 \
    --env-vars KC_DB=dev-file KEYCLOAK_ADMIN=admin KEYCLOAK_ADMIN_PASSWORD=admin \
      KC_HTTP_PORT=8080 KC_HTTP_RELATIVE_PATH=/auth KC_HOSTNAME_STRICT=false \
      KC_HEALTH_ENABLED=true KC_PROXY=edge \
      "KC_HOSTNAME_URL=https://placeholder.invalid/auth"

echo "── service-b / service-a (mTLS, certs volume via YAML) ──"
bash deploy/azure/create-app-with-certs.sh service-b "$IMG/zteasy-service-b:$TAG" 8082 \
    "ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET"
bash deploy/azure/create-app-with-certs.sh service-a "$IMG/zteasy-service-a:$TAG" 8081 \
    "ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET SERVICE_B_URI=https://service-b.$INTERNAL:8082"

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
      --secrets "anthropic-key=$ANTHROPIC_API_KEY" \
      --env-vars "ANTHROPIC_API_KEY=secretref:anthropic-key" \
        "GATEWAY_INTERNAL_URI=https://gateway.$INTERNAL:8080"
fi

echo "── gateway (phase-1 create → learn FQDN) ──"
bash deploy/azure/create-app-with-certs.sh gateway "$IMG/zteasy-gateway:$TAG" 8080 \
    "DB_HOST=postgres.$INTERNAL DB_PORT=5432 DB_NAME=zte_db DB_USER=zte_user DB_PASSWORD=zte_pass \
     KEYCLOAK_JWKS_URI=http://keycloak.$INTERNAL:8080/auth/realms/zte-realm/protocol/openid-connect/certs \
     ZTE_AUTH_PROXY_ENABLED=true ZTE_AUTH_PROXY_URI=http://keycloak.$INTERNAL:8080 \
     ZTE_IDP_KEYCLOAK_BASE_URI=http://keycloak.$INTERNAL:8080/auth \
     SERVICE_A_URI=https://service-a.$INTERNAL:8081 SERVICE_B_URI=https://service-b.$INTERNAL:8082 \
     MCP_BACKEND_URI=http://mcp-bridge.$INTERNAL:9090 \
     ZTE_CERTS_DIR=/app/certs ZTE_OBO_SECRET=$OBO_SECRET \
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
# restart both so keycloak re-imports the origin-correct realm and the
# gateway reloads the FQDN-SAN server cert
$AZ containerapp revision restart -n keycloak -g "$RG" \
    --revision "$($AZ containerapp revision list -n keycloak -g "$RG" --query '[0].name' -o tsv)" -o none || true
$AZ containerapp revision restart -n gateway -g "$RG" \
    --revision "$($AZ containerapp revision list -n gateway -g "$RG" --query '[0].name' -o tsv)" -o none || true

echo "── agent-runner job (manual trigger) ──"
$AZ containerapp job create -n agent-runner -g "$RG" --environment "$ENV_NAME" \
    --trigger-type Manual --replica-timeout 600 --replica-retry-limit 0 \
    --image "$IMG/hubspot-mcp-agents:$TAG" --cpu 0.25 --memory 0.5Gi \
    "${REG_ARGS[@]}" \
    --env-vars "KEYCLOAK_TOKEN_URL=http://keycloak.$INTERNAL:8080/auth/realms/zte-realm/protocol/openid-connect/token" \
      "GATEWAY_URL=https://gateway.$INTERNAL:8080" \
      GATEWAY_CLIENT_CERT=/app/certs/client.pem \
      AGENT_A_CLIENT_ID=agent-a AGENT_A_CLIENT_SECRET=agent-a-secret-dev-only \
      AGENT_B_CLIENT_ID=agent-b AGENT_B_CLIENT_SECRET=agent-b-secret-dev-only \
      AGENT_CRM_CLIENT_ID=crm-account-health-emea-01 \
      AGENT_CRM_CLIENT_SECRET=crm-account-health-emea-01-secret-dev-only \
    -o none 2>/dev/null || echo "(job exists — leaving as is)"
# attach the certs volume to the job (client.pem) — YAML-only operation
bash deploy/azure/attach-certs-to-job.sh agent-runner

echo
echo "══════════════════════════════════════════════════════"
echo " Admin Console:    $ORIGIN/admin/index.html   (zte-admin / Admin@123!)"
echo " Approval Center:  $ORIGIN/approver/index.html (zte-test-user / User@123!)"
echo " Demo run:         az containerapp job start -n agent-runner -g $RG"
echo "══════════════════════════════════════════════════════"
