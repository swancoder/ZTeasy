#!/usr/bin/env bash
# Adds the certs Azure Files volume mount to a Container Apps *Job* (the
# agent-runner needs certs/client.pem). Jobs, like apps, only accept volume
# mounts via YAML — this patches the job in place.
# Usage: attach-certs-to-job.sh <job-name>   (AZ, RG in env)
set -euo pipefail
NAME=$1
AZ="${AZ:-az}"
OUT="$(dirname "${BASH_SOURCE[0]}")/out/job-${NAME}.yaml"

$AZ containerapp job show -n "$NAME" -g "$RG" -o yaml > "$OUT"
python3 - "$OUT" <<'EOF'
import sys, yaml
path = sys.argv[1]
doc = yaml.safe_load(open(path))
tpl = doc["properties"]["template"]
tpl["volumes"] = [{"name": "certs", "storageName": "certs", "storageType": "AzureFile"}]
for c in tpl["containers"]:
    c["volumeMounts"] = [{"volumeName": "certs", "mountPath": "/app/certs"}]
yaml.safe_dump(doc, open(path, "w"))
EOF
$AZ containerapp job update -n "$NAME" -g "$RG" --yaml "$OUT" -o none
echo "job ${NAME}: certs volume attached"
