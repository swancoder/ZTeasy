#!/usr/bin/env bash
# Creates (idempotently) the `db-backup` job: pg_dump of the demo database onto
# the `pgbackup` Azure Files share, as `10-restore.sql`.
#
# Why a dump and not a data volume: Postgres cannot run its data directory on
# SMB Azure Files — initdb fails with "could not change permissions of
# directory ... Operation not permitted" (verified on this deployment,
# ADR-033). NFS would work but needs a Premium FileStorage account, which the
# free-tier demo deliberately doesn't have.
#
# The restore side needs no code: the same share is mounted READ-ONLY on the
# postgres app at /docker-entrypoint-initdb.d, and the official image runs
# whatever *.sql it finds there whenever it initialises an empty data
# directory — which, with ephemeral storage, is every single start.
#
# Usage: db-backup-job.sh          (AZ, RG, ENV_NAME in env)
set -euo pipefail
AZ="${AZ:-az}"
RG="${RG:-zteasy-demo-rg}"
ENV_NAME="${ENV_NAME:-zteasy-env-v2}"
NAME=db-backup

$AZ containerapp job create -n "$NAME" -g "$RG" --environment "$ENV_NAME" \
    --trigger-type Manual --replica-timeout 300 --replica-retry-limit 0 \
    --image postgres:16-alpine --cpu 0.25 --memory 0.5Gi \
    --env-vars PGPASSWORD=zte_pass \
    -o none 2>/dev/null || echo "(job exists — updating)"

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
tpl["volumes"] = [{"name": "pgbackup", "storageName": "pgbackup", "storageType": "AzureFile"}]
# command/args go in the ARM document, not through `az ... --args`: the CLI
# splits that value on commas, which turns `-c <script>` into one bogus
# argument (`/bin/sh: illegal option -,`).
DUMP = (
    "set -e; "
    "pg_dump -h postgres -U zte_user -d zte_db --no-owner --no-acl "
    "-f /backup/10-restore.sql.tmp; "
    # Rename last: the postgres app mounts this share as its init directory, so
    # a half-written file must never be visible under the name it restores from.
    "mv /backup/10-restore.sql.tmp /backup/10-restore.sql; "
    "echo backup-ok $(wc -c < /backup/10-restore.sql) bytes"
)
for c in tpl["containers"]:
    c["volumeMounts"] = [{"volumeName": "pgbackup", "mountPath": "/backup"}]
    c["command"] = ["/bin/sh"]
    c["args"] = ["-c", DUMP]
json.dump(doc, open(os.environ["OUT"], "w"), indent=2)
EOF
$AZ rest --method put --url "$URL" --body "@${OUT}" -o none
echo "job ${NAME}: ready (writes /backup/10-restore.sql on the pgbackup share)"
