#!/usr/bin/env bash
# Creates/updates one Container App that mounts the shared certs Azure Files
# volume (ADR-027), via a raw ARM PUT (`az rest`) against the STABLE
# 2024-03-01 api-version. The containerapp CLI extension's own `--yaml` path
# builds its envelope for a preview api-version that the North Europe RP
# rejected outright ("$ ... System.Boolean" 400, seen live 2026-08-25) —
# `az rest` gives us full control over both body and api-version.
#
# Usage: create-app-with-certs.sh <name> <image> <port> "<ENV=val ENV2=val…>" [external]
# Expects: AZ, RG, ENV_NAME, LOC, GHCR_USER, GHCR_PAT in the environment.
set -euo pipefail

NAME=$1 IMAGE=$2 PORT=$3 ENVS=$4 EXTERNAL=${5:-internal}
AZ="${AZ:-az}"
ENV_ID=$($AZ containerapp env show -n "$ENV_NAME" -g "$RG" --query id -o tsv)
SUB_ID=$($AZ account show --query id -o tsv)
OUT="$(dirname "${BASH_SOURCE[0]}")/out/app-${NAME}.json"
mkdir -p "$(dirname "$OUT")"

NAME="$NAME" IMAGE="$IMAGE" PORT="$PORT" ENVS="$ENVS" EXTERNAL="$EXTERNAL" \
ENV_ID="$ENV_ID" LOC="${LOC:-northeurope}" OUT="$OUT" python3 - <<'EOF'
import json, os

envs = [{"name": kv.split("=", 1)[0], "value": kv.split("=", 1)[1]}
        for kv in os.environ["ENVS"].split()]
port = int(os.environ["PORT"])
doc = {
    "location": os.environ["LOC"],
    "properties": {
        "environmentId": os.environ["ENV_ID"],
        "configuration": {
            "ingress": {
                "external": os.environ["EXTERNAL"] == "external",
                "transport": "tcp",
                "targetPort": port,
                "exposedPort": port,
            },
            "registries": [{
                "server": "ghcr.io",
                "username": os.environ["GHCR_USER"],
                "passwordSecretRef": "ghcr-pat",
            }],
            "secrets": [{"name": "ghcr-pat", "value": os.environ["GHCR_PAT"]}],
        },
        "template": {
            "containers": [{
                "name": os.environ["NAME"],
                "image": os.environ["IMAGE"],
                "resources": {"cpu": 0.5, "memory": "1Gi"},
                "env": envs,
                "volumeMounts": [{"volumeName": "certs", "mountPath": "/app/certs"}],
            }],
            "volumes": [{"name": "certs", "storageName": "certs", "storageType": "AzureFile"}],
            "scale": {"minReplicas": 1, "maxReplicas": 1},
        },
    },
}
with open(os.environ["OUT"], "w") as f:
    json.dump(doc, f, indent=2)
EOF

URL="https://management.azure.com/subscriptions/${SUB_ID}/resourceGroups/${RG}/providers/Microsoft.App/containerApps/${NAME}?api-version=2024-03-01"
$AZ rest --method put --url "$URL" --body "@${OUT}" -o none

# Wait for provisioning to settle so a dependent step can read the fqdn
for i in $(seq 1 30); do
  STATE=$($AZ containerapp show -n "$NAME" -g "$RG" --query properties.provisioningState -o tsv 2>/dev/null || echo "")
  case "$STATE" in
    Succeeded) break ;;
    Failed|Canceled) echo "app ${NAME}: provisioning $STATE" >&2; exit 1 ;;
    *) sleep 10 ;;
  esac
done
echo "app ${NAME}: created/updated (${EXTERNAL}, state=${STATE})"
