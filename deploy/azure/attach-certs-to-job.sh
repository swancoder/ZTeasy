#!/usr/bin/env bash
# Adds the certs Azure Files volume mount to a Container Apps *Job* (the
# agent-runner needs certs/client.pem). Same raw-ARM approach as
# create-app-with-certs.sh (stable api-version, az rest) — the CLI's YAML
# path is broken against this RP (see that script's header).
# Usage: attach-certs-to-job.sh <job-name>   (AZ, RG in env)
set -euo pipefail
NAME=$1
AZ="${AZ:-az}"
SUB_ID=$($AZ account show --query id -o tsv)
URL="https://management.azure.com/subscriptions/${SUB_ID}/resourceGroups/${RG}/providers/Microsoft.App/jobs/${NAME}?api-version=2024-03-01"
OUT="$(dirname "${BASH_SOURCE[0]}")/out/job-${NAME}.json"
mkdir -p "$(dirname "$OUT")"

$AZ rest --method get --url "$URL" > "$OUT.full"
OUT="$OUT" python3 - <<'EOF'
import json, os
full = json.load(open(os.environ["OUT"] + ".full"))
doc = {"location": full["location"], "properties": full["properties"]}
tpl = doc["properties"]["template"]
tpl["volumes"] = [{"name": "certs", "storageName": "certs", "storageType": "AzureFile"}]
for c in tpl["containers"]:
    c["volumeMounts"] = [{"volumeName": "certs", "mountPath": "/app/certs"}]
# ARM PUT is a full replace and GET returns secrets with value:null — the
# only job secret is the GHCR registry password, so re-supply it from env
# (dropping it would break the registry's passwordSecretRef).
cfg = doc["properties"].get("configuration") or {}
for secret in cfg.get("secrets") or []:
    if secret.get("value") is None:
        secret["value"] = os.environ["GHCR_PAT"]
json.dump(doc, open(os.environ["OUT"], "w"), indent=2)
EOF
$AZ rest --method put --url "$URL" --body "@${OUT}" -o none
echo "job ${NAME}: certs volume attached"
