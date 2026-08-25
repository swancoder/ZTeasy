#!/usr/bin/env bash
# Builds and pushes every ZTeasy image to GHCR (ADR-027). Keycloak's image is
# NOT built here — it needs the real gateway origin first (deploy.sh phase 2).
# Usage: GHCR_USER=<user> GHCR_PAT=<token> [TAG=azure-1] ./push-images.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

GHCR_USER="${GHCR_USER:?set GHCR_USER}"
IMG="ghcr.io/${GHCR_USER}"
TAG="${TAG:-azure-1}"

echo "${GHCR_PAT:?set GHCR_PAT (write:packages)}" | docker login ghcr.io -u "$GHCR_USER" --password-stdin

docker build -f gateway-service/Dockerfile -t "$IMG/zteasy-gateway:$TAG" .
docker build -f service-a/Dockerfile       -t "$IMG/zteasy-service-a:$TAG" .
docker build -f service-b/Dockerfile       -t "$IMG/zteasy-service-b:$TAG" .
docker build -f zt-agents/Dockerfile       -t "$IMG/zteasy-zt-agents:$TAG" .
docker build -f ../hubspot-mcp/Dockerfile.bridge -t "$IMG/hubspot-mcp-bridge:$TAG" ../hubspot-mcp
docker build -f ../hubspot-mcp/Dockerfile.agents -t "$IMG/hubspot-mcp-agents:$TAG" ../hubspot-mcp

for image in zteasy-gateway zteasy-service-a zteasy-service-b zteasy-zt-agents hubspot-mcp-bridge hubspot-mcp-agents; do
  docker push "$IMG/$image:$TAG"
done
echo "pushed 6 images to $IMG/*:$TAG (keep them PRIVATE — see ADR-027)"
