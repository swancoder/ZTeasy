#!/usr/bin/env bash
# Creates/updates one Container App that mounts the shared certs Azure Files
# volume (ADR-027). Volume mounts are a YAML-only feature of the containerapp
# CLI, hence this generator instead of plain `az containerapp create` flags.
#
# Usage: create-app-with-certs.sh <name> <image> <port> "<ENV=val ENV2=val…>" [external]
# Expects: AZ, RG, ENV_NAME, GHCR_USER, GHCR_PAT in the environment (deploy.sh sets them).
set -euo pipefail

NAME=$1 IMAGE=$2 PORT=$3 ENVS=$4 EXTERNAL=${5:-internal}
AZ="${AZ:-az}"
ENV_ID=$($AZ containerapp env show -n "$ENV_NAME" -g "$RG" --query id -o tsv)
OUT="$(dirname "${BASH_SOURCE[0]}")/out/app-${NAME}.yaml"
mkdir -p "$(dirname "$OUT")"

{
  cat <<EOF
properties:
  environmentId: ${ENV_ID}
  configuration:
    ingress:
      external: $( [ "$EXTERNAL" = external ] && echo true || echo false )
      transport: tcp
      targetPort: ${PORT}
      exposedPort: ${PORT}
    registries:
      - server: ghcr.io
        username: ${GHCR_USER}
        passwordSecretRef: ghcr-pat
    secrets:
      - name: ghcr-pat
        value: ${GHCR_PAT}
  template:
    containers:
      - name: ${NAME}
        image: ${IMAGE}
        resources:
          cpu: 0.5
          memory: 1Gi
        volumeMounts:
          - volumeName: certs
            mountPath: /app/certs
        env:
EOF
  for kv in $ENVS; do
    printf '          - name: %s\n            value: "%s"\n' "${kv%%=*}" "${kv#*=}"
  done
  cat <<EOF
    volumes:
      - name: certs
        storageName: certs
        storageType: AzureFile
    scale:
      minReplicas: 1
      maxReplicas: 1
EOF
} > "$OUT"

$AZ containerapp create -n "$NAME" -g "$RG" --yaml "$OUT" -o none
echo "app ${NAME}: created/updated (${EXTERNAL})"
