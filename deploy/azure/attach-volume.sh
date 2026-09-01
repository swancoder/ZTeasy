#!/usr/bin/env bash
# Attaches an Azure Files volume to a Container *App* (not a job — see
# attach-certs-to-job.sh for that), optionally setting extra env vars in the
# same revision. Raw ARM PUT for the same reason as create-app-with-certs.sh:
# the CLI's --yaml path is rejected by this RP.
#
# Usage: attach-volume.sh <app> <storage-name> <mount-path|-> <revision-suffix> [KEY=VAL ...]
#        A mount-path of '-' detaches that storage instead.
# Env:   AZ, RG. Any secret the app holds must be re-supplied via a
#        <SECRET_NAME_UPPER>_VALUE env var — ARM PUT is a full replace and GET
#        returns secret values as null (learned the hard way, ADR-027).
set -euo pipefail
APP=$1; STORAGE=$2; MOUNT=$3; SUFFIX=$4; shift 4
AZ="${AZ:-az}"
SUB_ID=$($AZ account show --query id -o tsv)
URL="https://management.azure.com/subscriptions/${SUB_ID}/resourceGroups/${RG}/providers/Microsoft.App/containerApps/${APP}?api-version=2024-03-01"
OUT="$(dirname "${BASH_SOURCE[0]}")/out/app-${APP}.json"
mkdir -p "$(dirname "$OUT")"

$AZ rest --method get --url "$URL" > "$OUT.full"
OUT="$OUT" STORAGE="$STORAGE" MOUNT="$MOUNT" SUFFIX="$SUFFIX" EXTRA_ENV="$*" python3 - <<'EOF'
import json, os
full = json.load(open(os.environ["OUT"] + ".full"))
doc  = {"location": full["location"], "properties": full["properties"]}
tpl  = doc["properties"]["template"]
name = os.environ["STORAGE"]

detach = os.environ["MOUNT"] == "-"

vols = [v for v in (tpl.get("volumes") or []) if v.get("name") != name]
if not detach:
    vols.append({"name": name, "storageName": name, "storageType": "AzureFile"})
tpl["volumes"] = vols or None

for c in tpl["containers"]:
    mounts = [m for m in (c.get("volumeMounts") or []) if m.get("volumeName") != name]
    if not detach:
        mounts.append({"volumeName": name, "mountPath": os.environ["MOUNT"]})
    c["volumeMounts"] = mounts or None
    env = {e["name"]: e for e in (c.get("env") or [])}
    for kv in os.environ["EXTRA_ENV"].split():
        if not kv:
            continue
        k, _, v = kv.partition("=")
        env[k] = {"name": k, "value": v}
    c["env"] = list(env.values())

# A repeated revisionSuffix is rejected as a duplicate revision, and the app
# then replays the *old* failure on every later PUT until a fresh one is given.
tpl["revisionSuffix"] = os.environ["SUFFIX"]

for secret in (doc["properties"].get("configuration") or {}).get("secrets") or []:
    if secret.get("value") is None:
        var = secret["name"].replace("-", "_").upper() + "_VALUE"
        if var not in os.environ:
            raise SystemExit(f"secret '{secret['name']}' needs {var} in the environment")
        secret["value"] = os.environ[var]

json.dump(doc, open(os.environ["OUT"], "w"), indent=2)
EOF
$AZ rest --method put --url "$URL" --body "@${OUT}" -o none
echo "${APP}: '${STORAGE}' mounted at ${MOUNT} (revision ${SUFFIX})"
