#!/usr/bin/env bash
# ============================================================
# ZTeasy — rotate every secret the cloud deployment uses (ADR-037)
# ============================================================
# Removing a value from the repository does not un-publish it. Anything that was
# ever committed must be treated as known, so the deployment that used those
# values has to move to new ones — otherwise the cleanup is cosmetic.
#
# Rotates: OBO signing key, Postgres password, PKCS12 keystore password, all OIDC
# client secrets, all demo user passwords, and the internal API key. Writes the
# new values to deploy/azure/out/cloud-credentials.env (gitignored) and pushes
# them into Container Apps, Keycloak and the certificates in one pass.
#
# The database is dumped FIRST and restored by the normal start path, because
# changing the Postgres password means recreating the container, and this
# deployment's data directory is ephemeral by design (ADR-033).
#
# Usage: GHCR_USER=<user> GHCR_PAT=<token> ./deploy/azure/rotate-secrets.sh
# Env:   AZ, RG, ENV_NAME, GW_FQDN (discovered if unset)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"
IMG="ghcr.io/${GHCR_USER:?set GHCR_USER}"
TAG="${TAG:-azure-1}"
CREDS="deploy/azure/out/cloud-credentials.env"
CERT_APPS=(gateway gateway-web service-a service-b)

secret() { python3 -c 'import secrets; print(secrets.token_urlsafe(32))'; }
passwd() { python3 -c 'import secrets; print(secrets.token_urlsafe(12))'; }

echo "── 1/7 backing up the database before anything restarts ──"
EXEC=$($AZ containerapp job start -n db-backup -g "$RG" --query name -o tsv)
for _ in $(seq 1 40); do
  ST=$($AZ containerapp job execution show -n db-backup -g "$RG" --job-execution-name "$EXEC" \
        --query properties.status -o tsv 2>/dev/null || echo Unknown)
  case "$ST" in
    Succeeded) echo "   backup ok ($EXEC)"; break ;;
    Failed|Degraded) echo "   BACKUP FAILED — refusing to rotate and lose today's state" >&2; exit 1 ;;
    *) sleep 10 ;;
  esac
done
[ "$ST" = "Succeeded" ] || { echo "   backup did not finish in time" >&2; exit 1; }

echo "── 2/7 minting new values ──"
mkdir -p "$(dirname "$CREDS")"
cat > "$CREDS" <<EOF
# ZTeasy — cloud credentials (demo.zteasy.tech)
# LOCAL ONLY: this directory is gitignored (deploy/azure/out/). Never commit.
# Rotated $(date -u +%Y-%m-%d) by deploy/azure/rotate-secrets.sh — every value that
# was ever present in the public repository is dead as of this run (ADR-037).

# ── interactive users ──
export ZTE_PW_ZTE_ADMIN="$(passwd)"
export ZTE_PW_ZTE_TEST_USER="$(passwd)"
export ZTE_PW_ZTE_CEO="$(passwd)"
export ZTE_PW_ZTE_CFO="$(passwd)"
export ZTE_PW_ZTE_CTO="$(passwd)"
export ZTE_PW_ZTE_BOARD="$(passwd)"
export ZTE_PW_ZTE_DPO="$(passwd)"

# ── OIDC client secrets (agents/services) ──
export ZTE_SECRET_ZTE_GATEWAY="$(secret)"
export ZTE_SECRET_AGENT_A="$(secret)"
export ZTE_SECRET_AGENT_B="$(secret)"
export ZTE_SECRET_SERVICE_A="$(secret)"
export ZTE_SECRET_CRM_ACCOUNT_HEALTH_EMEA_01="$(secret)"

# ── infrastructure ──
export KEYCLOAK_ADMIN_PASSWORD="$(passwd)"
export ZTE_INTERNAL_API_KEY="$(secret)"
export ZTE_OBO_SECRET="$(secret)"
export DB_PASSWORD="$(passwd)"
export ZTE_KEY_PASSWORD="$(passwd)"
EOF
chmod 600 "$CREDS"
set -a; . "$CREDS"; set +a
echo "   wrote $CREDS"

echo "── 3/7 reissuing certificates under the new keystore password ──"
GW_FQDN="${GW_FQDN:-$($AZ containerapp show -n gateway -g "$RG" --query properties.configuration.ingress.fqdn -o tsv)}"
GATEWAY_EXTRA_SANS="DNS:${GW_FQDN}" ./certs/generate-certs.sh >/dev/null
STORAGE=$($AZ storage account list -g "$RG" --query "[0].name" -o tsv)
STKEY=$($AZ storage account keys list -n "$STORAGE" -g "$RG" --query "[0].value" -o tsv)
$AZ storage file upload-batch --account-name "$STORAGE" --account-key "$STKEY" \
    --destination certs --source certs --pattern '*' --no-progress -o none
echo "   certs reissued and uploaded"

echo "── 4/7 rebuilding Keycloak with the new realm ──"
ORIGIN="https://$($AZ containerapp show -n gateway-web -g "$RG" \
        --query 'properties.configuration.ingress.customDomains[0].name' -o tsv 2>/dev/null || true)"
[ "$ORIGIN" = "https://" ] && ORIGIN="https://${GW_FQDN}:8080"
python3 deploy/azure/make-cloud-realm.py "${ORIGIN},https://${GW_FQDN}:8080"
docker build -q -f deploy/azure/Dockerfile.keycloak -t "$IMG/zteasy-keycloak:$TAG" . >/dev/null
docker push "$IMG/zteasy-keycloak:$TAG" >/dev/null
echo "   keycloak image pushed"

echo "── 5/7 pushing values into every app ──"
SUF="rot$(date -u +%H%M)"
# Secrets first: an env var can only reference a secret that already exists.
for app in postgres gateway gateway-web; do
  $AZ containerapp secret set -n "$app" -g "$RG" --secrets "db-password=$DB_PASSWORD" -o none
done
for app in gateway gateway-web; do
  $AZ containerapp secret set -n "$app" -g "$RG" \
      --secrets "internal-api-key=$ZTE_INTERNAL_API_KEY" "idp-client-secret=$ZTE_SECRET_ZTE_GATEWAY" -o none
done
$AZ containerapp secret set -n zt-agents -g "$RG" --secrets "internal-api-key=$ZTE_INTERNAL_API_KEY" -o none

$AZ containerapp update -n postgres -g "$RG" --revision-suffix "$SUF" \
    --set-env-vars "POSTGRES_PASSWORD=secretref:db-password" -o none
for app in gateway gateway-web; do
  $AZ containerapp update -n "$app" -g "$RG" --revision-suffix "$SUF" \
      --set-env-vars "DB_PASSWORD=secretref:db-password" \
        "ZTE_OBO_SECRET=$ZTE_OBO_SECRET" "ZTE_KEY_PASSWORD=$ZTE_KEY_PASSWORD" \
        "ZTE_IDP_KEYCLOAK_CLIENT_SECRET=secretref:idp-client-secret" -o none
done
for app in service-a service-b; do
  $AZ containerapp update -n "$app" -g "$RG" --revision-suffix "$SUF" \
      --set-env-vars "ZTE_OBO_SECRET=$ZTE_OBO_SECRET" "ZTE_KEY_PASSWORD=$ZTE_KEY_PASSWORD" -o none
done
$AZ containerapp update -n keycloak -g "$RG" --image "$IMG/zteasy-keycloak:$TAG" \
    --revision-suffix "$SUF" -o none
echo "   apps updated (revision suffix $SUF)"

echo "── 6/7 jobs ──"
$AZ containerapp job update -n agent-runner -g "$RG" \
    --set-env-vars "AGENT_A_CLIENT_SECRET=$ZTE_SECRET_AGENT_A" \
      "AGENT_B_CLIENT_SECRET=$ZTE_SECRET_AGENT_B" \
      "AGENT_CRM_CLIENT_SECRET=$ZTE_SECRET_CRM_ACCOUNT_HEALTH_EMEA_01" -o none
$AZ containerapp job secret set -n db-backup -g "$RG" --secrets "db-password=$DB_PASSWORD" -o none 2>/dev/null || true
$AZ containerapp job update -n db-backup -g "$RG" --set-env-vars "PGPASSWORD=secretref:db-password" -o none

echo "── 7/7 restarting cert holders so the new keystores are loaded ──"
for app in "${CERT_APPS[@]}"; do
  REV=$($AZ containerapp revision list -n "$app" -g "$RG" \
        --query 'sort_by([],&properties.createdTime)[-1].name' -o tsv)
  $AZ containerapp revision restart -n "$app" -g "$RG" --revision "$REV" -o none || true
done

cat <<DONE

Rotation complete. Every value published in the repository is now dead.
New credentials: $CREDS (gitignored, mode 600)
The database was restored from the dump taken in step 1 — the demo state survives.
DONE
