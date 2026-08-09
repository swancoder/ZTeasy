#!/usr/bin/env bash
# ============================================================
# set-keycloak-password.sh
# Sets the initial password for zte-admin in zte-realm.
#
# Run once after `docker compose up -d` and Keycloak is healthy.
# Usage: ./scripts/set-keycloak-password.sh [password]
# Default password: Admin@123!
# ============================================================
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
# kcadm.sh runs via `docker exec`, i.e. *inside* the container's own network
# namespace — the host-side 8180 mapping doesn't exist there, only the
# container's actual listener (KC_HTTP_PORT, 8080).
KEYCLOAK_INTERNAL_URL="${KEYCLOAK_INTERNAL_URL:-http://localhost:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="zte-realm"
TARGET_USER="zte-admin"
TARGET_PASS="${1:-Admin@123!}"

echo "[zte-init] Waiting for Keycloak at ${KEYCLOAK_URL} ..."
until curl -sf "${KEYCLOAK_URL}/health/ready" > /dev/null 2>&1; do
  sleep 2
done
echo "[zte-init] Keycloak is ready."

echo "[zte-init] Authenticating admin CLI ..."
docker exec zte-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server "${KEYCLOAK_INTERNAL_URL}" \
  --realm master \
  --user "${ADMIN_USER}" \
  --password "${ADMIN_PASS}"

echo "[zte-init] Setting password for user '${TARGET_USER}' in realm '${REALM}' ..."
docker exec zte-keycloak /opt/keycloak/bin/kcadm.sh set-password \
  --target-realm "${REALM}" \
  --username "${TARGET_USER}" \
  --new-password "${TARGET_PASS}"

echo "[zte-init] Done. Login: username=${TARGET_USER}, password=${TARGET_PASS}"
echo "[zte-init] Token endpoint: ${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token"
