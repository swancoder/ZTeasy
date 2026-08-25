#!/usr/bin/env bash
# ============================================================
# ZTeasy — bind a custom domain + managed certificate (ADR-028)
# ============================================================
# Attaches <domain> to the browser-facing `gateway-web` app and issues a free,
# auto-renewing Azure managed certificate for it, then repoints Keycloak's
# issuer and both gateways at that domain.
#
# Prerequisite — two DNS records the domain owner must add first:
#   CNAME  demo        -> gateway-web.<env>.<region>.azurecontainerapps.io
#   TXT    asuid.demo  -> <customDomainVerificationId>   (printed by this script)
# Run with no arguments to just print what's needed and exit.
#
# Usage:
#   ./deploy/azure/bind-custom-domain.sh                 # show required DNS records
#   ./deploy/azure/bind-custom-domain.sh demo.zteasy.tech
#
# Env: AZ, RG (default zteasy-demo-rg), ENV_NAME (default zteasy-env-v2),
#      WEB_APP (default gateway-web), TCP_APP (default gateway),
#      IMG/TAG (for rebuilding the Keycloak realm image)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"
ENV_NAME="${ENV_NAME:-zteasy-env-v2}"
WEB_APP="${WEB_APP:-gateway-web}"
TCP_APP="${TCP_APP:-gateway}"
IMG="${IMG:-ghcr.io/${GHCR_USER:-swancoder}}"
TAG="${TAG:-azure-1}"

WEB_FQDN=$($AZ containerapp show -n "$WEB_APP" -g "$RG" --query properties.configuration.ingress.fqdn -o tsv)
VERIFICATION_ID=$($AZ containerapp show -n "$WEB_APP" -g "$RG" --query properties.customDomainVerificationId -o tsv)

if [ $# -lt 1 ]; then
  cat <<EOF
Add these two records at the domain's DNS provider, then re-run with the domain:

  CNAME   <subdomain>         ${WEB_FQDN}.
  TXT     asuid.<subdomain>   ${VERIFICATION_ID}

  e.g.  ./deploy/azure/bind-custom-domain.sh demo.zteasy.tech
EOF
  exit 0
fi

DOMAIN=$1
SUBDOMAIN=${DOMAIN%%.*}
ORIGIN="https://${DOMAIN}"

echo "── checking DNS ──"
CNAME_TARGET=$(dig +short "$DOMAIN" CNAME | sed 's/\.$//')
TXT_VALUE=$(dig +short TXT "asuid.${SUBDOMAIN}.${DOMAIN#*.}" | tr -d '"')
[ "$CNAME_TARGET" = "$WEB_FQDN" ] || { echo "CNAME for $DOMAIN is '${CNAME_TARGET:-missing}', expected '$WEB_FQDN'" >&2; exit 1; }
[ "$TXT_VALUE" = "$VERIFICATION_ID" ] || { echo "asuid TXT is '${TXT_VALUE:-missing}', expected '$VERIFICATION_ID'" >&2; exit 1; }
echo "both records look right"

echo "── binding domain + managed certificate ──"
# hostname add registers the domain; bind issues/attaches the managed cert.
# Both are idempotent enough to re-run after a partial failure.
$AZ containerapp hostname add -n "$WEB_APP" -g "$RG" --hostname "$DOMAIN" -o none 2>/dev/null || true
$AZ containerapp hostname bind -n "$WEB_APP" -g "$RG" --hostname "$DOMAIN" \
    --environment "$ENV_NAME" --validation-method CNAME -o none

echo "── repointing Keycloak and both gateways at $ORIGIN ──"
TCP_FQDN=$($AZ containerapp show -n "$TCP_APP" -g "$RG" --query properties.configuration.ingress.fqdn -o tsv)
TCP_PORT=$($AZ containerapp show -n "$TCP_APP" -g "$RG" --query properties.configuration.ingress.exposedPort -o tsv)
# The realm keeps redirect URIs for both front doors; only the issuer is single.
python3 deploy/azure/make-cloud-realm.py "${ORIGIN},https://${TCP_FQDN}:${TCP_PORT}"
docker build -f deploy/azure/Dockerfile.keycloak -t "$IMG/zteasy-keycloak:$TAG" .
docker push "$IMG/zteasy-keycloak:$TAG"

$AZ containerapp update -n keycloak -g "$RG" --set-env-vars "KC_HOSTNAME_URL=${ORIGIN}/auth" -o none
for app in "$WEB_APP" "$TCP_APP"; do
  $AZ containerapp update -n "$app" -g "$RG" \
      --set-env-vars "KEYCLOAK_ISSUER_URI=${ORIGIN}/auth/realms/zte-realm" \
        "ZTE_UI_OIDC_AUTHORITY=${ORIGIN}/auth/realms/zte-realm" -o none
done
# Keycloak's realm import only happens on a fresh container, so restart it to
# pick up the regenerated realm (its H2 store is ephemeral by design, ADR-027).
REV=$($AZ containerapp revision list -n keycloak -g "$RG" \
      --query 'sort_by([],&properties.createdTime)[-1].name' -o tsv)
$AZ containerapp revision restart -n keycloak -g "$RG" --revision "$REV" -o none || true

echo
echo "══════════════════════════════════════════════════════"
echo " Admin Console:   ${ORIGIN}/admin/index.html"
echo " Approval Center: ${ORIGIN}/approver/index.html"
echo " Agents (mTLS):   https://${TCP_FQDN}:${TCP_PORT}  (unchanged)"
echo "══════════════════════════════════════════════════════"
