#!/usr/bin/env bash
# =============================================================================
# ZTE Development Certificate Generator
# =============================================================================
# Generates a self-signed CA and service certificates for local mTLS development.
#
# Prerequisites: openssl >= 1.1.1, keytool (JDK)
#
# Output (all in this directory):
#   ca.crt              — ZTE root CA certificate (public, safe to distribute)
#   ca.key              — ZTE CA private key (keep secret)
#   client.p12          — PKCS12 keystore for internal client auth
#                         (used by gateway + service-a for outbound mTLS calls)
#   client.pem          — same client cert+key as client.p12, combined PEM form
#                         (for non-JVM clients, e.g. hubspot-mcp's agent_simulator.py —
#                         see the security note at its generation step below)
#   service-a.p12       — PKCS12 keystore for service-a's HTTPS server
#   service-b.p12       — PKCS12 keystore for service-b's HTTPS server
#   gateway.p12         — PKCS12 keystore for gateway-service's HTTPS server
#                         (ADR-018: server.ssl.client-auth=want — see MtlsEnforcementWebFilter)
#   truststore.p12      — PKCS12 truststore containing only the ZTE CA cert
#                         (used by all services to trust peer certificates)
#
# Usage:
#   chmod +x certs/generate-certs.sh
#   ./certs/generate-certs.sh
#
# Re-running is safe: an existing CA (ca.crt/ca.key) is reused and only the
# service/client certs are reissued, so peers already trusting that CA keep
# working. Set ZTE_REGENERATE_CA=1 to mint a new CA instead — that
# invalidates every previously issued cert, so restart every service
# afterwards. GATEWAY_EXTRA_SANS adds SANs to the gateway's server cert
# (e.g. GATEWAY_EXTRA_SANS=DNS:my-host.example.com).
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS="${ZTE_KEY_PASSWORD:?set ZTE_KEY_PASSWORD — run scripts/generate-dev-secrets.sh, or source deploy/azure/out/cloud-credentials.env}"
DAYS_CA=3650   # 10 years — root CA, rotate with full PKI overhaul
DAYS_SVC=365   # 1 year — service certs; automate rotation via CI/CD
SUBJ_BASE="/C=IL/ST=Dev/L=Dev/O=ZTE-Lightweight"

# Colour helpers
GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

info "Generating ZTE development certificates in: $DIR"
info "Key password: taken from ZTE_KEY_PASSWORD (not printed — ADR-037)"
cd "$DIR"

# ── Verify tooling ──────────────────────────────────────────────────────────
for cmd in openssl keytool; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: '$cmd' not found. Install OpenSSL and a JDK." >&2
        exit 1
    fi
done

# ── 1. ZTE Root CA ──────────────────────────────────────────────────────────
# Reused when it already exists. Re-running this script is a normal thing to
# do — reissuing one service cert (a new SAN, an expiry) shouldn't invalidate
# every other cert already deployed elsewhere. A fresh CA silently breaks
# every peer still holding the old one, which surfaces far away from here as
# "PKIX path validation failed: Path does not chain with any of the trust
# anchors" (hit live on the Azure deployment, ADR-027). Force a brand-new CA
# with ZTE_REGENERATE_CA=1 — then every service holding a cert must be
# restarted with the regenerated files.
if [[ -f ca.crt && -f ca.key && "${ZTE_REGENERATE_CA:-0}" != "1" ]]; then
    CA_EXPIRY=$(openssl x509 -in ca.crt -noout -enddate | cut -d= -f2)
    info "1/6  Reusing existing ZTE Root CA (expires ${CA_EXPIRY}; ZTE_REGENERATE_CA=1 to replace)"
else
    if [[ -f ca.crt ]]; then
        warn "Replacing the existing ZTE Root CA — every service holding a cert signed by"
        warn "the old CA must be restarted with the newly generated files, or mTLS will fail."
    fi
    info "1/6  Generating ZTE Root CA (${DAYS_CA}d) ..."
    openssl req -x509 -newkey rsa:4096 \
        -keyout ca.key -out ca.crt \
        -days $DAYS_CA -nodes \
        -subj "${SUBJ_BASE}/CN=ZTE-CA"
fi

# ── 2. Internal Client Certificate ─────────────────────────────────────────
# Used by gateway (outbound to service-a/b) and service-a (outbound to service-b)
info "2/6  Generating internal client certificate ..."
openssl req -newkey rsa:2048 \
    -keyout client.key -out client.csr \
    -nodes -subj "${SUBJ_BASE}/CN=zte-internal-client"

openssl x509 -req \
    -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out client.crt -days $DAYS_SVC \
    -extfile <(echo "extendedKeyUsage=clientAuth")

openssl pkcs12 -export \
    -in client.crt -inkey client.key \
    -certfile ca.crt \
    -out client.p12 -passout "pass:${PASS}" \
    -name "zte-internal-client"

# Combined cert+unencrypted-key PEM — most non-JVM HTTP clients (Python `requests`,
# curl's --cert without --cert-type, etc.) expect this shape, not PKCS12. An
# unencrypted private key sitting in a plain file is a real security tradeoff —
# acceptable only because this is a local dev CA with no production exposure;
# never do this for a real deployment. Consumed by hubspot-mcp's agent_simulator.py.
cat client.crt client.key > client.pem
chmod 600 client.pem

# ── 3. Service A Server Certificate ────────────────────────────────────────
info "3/6  Generating service-a server certificate ..."
openssl req -newkey rsa:2048 \
    -keyout service-a.key -out service-a.csr \
    -nodes -subj "${SUBJ_BASE}/CN=service-a"

openssl x509 -req \
    -in service-a.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out service-a.crt -days $DAYS_SVC \
    -extfile <(printf "subjectAltName=DNS:service-a,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth")

openssl pkcs12 -export \
    -in service-a.crt -inkey service-a.key \
    -certfile ca.crt \
    -out service-a.p12 -passout "pass:${PASS}" \
    -name "service-a"

# ── 4. Service B Server Certificate ────────────────────────────────────────
info "4/6  Generating service-b server certificate ..."
openssl req -newkey rsa:2048 \
    -keyout service-b.key -out service-b.csr \
    -nodes -subj "${SUBJ_BASE}/CN=service-b"

openssl x509 -req \
    -in service-b.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out service-b.crt -days $DAYS_SVC \
    -extfile <(printf "subjectAltName=DNS:service-b,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth")

openssl pkcs12 -export \
    -in service-b.crt -inkey service-b.key \
    -certfile ca.crt \
    -out service-b.p12 -passout "pass:${PASS}" \
    -name "service-b"

# ── 5. Gateway Server Certificate ──────────────────────────────────────────
# ADR-018: gateway-service's own HTTPS listener (server.ssl.client-auth=want).
info "5/6  Generating gateway server certificate ..."
openssl req -newkey rsa:2048 \
    -keyout gateway.key -out gateway.csr \
    -nodes -subj "${SUBJ_BASE}/CN=gateway"

openssl x509 -req \
    -in gateway.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out gateway.crt -days $DAYS_SVC \
    -extfile <(printf "subjectAltName=DNS:gateway,DNS:localhost,IP:127.0.0.1%s\nextendedKeyUsage=serverAuth" "${GATEWAY_EXTRA_SANS:+,${GATEWAY_EXTRA_SANS}}")

openssl pkcs12 -export \
    -in gateway.crt -inkey gateway.key \
    -certfile ca.crt \
    -out gateway.p12 -passout "pass:${PASS}" \
    -name "gateway"

# ── 6. MCP backend hop: its own server cert AND its own client identity ─────
# ADR-038. Everything else in this PKI shares one client identity
# (client.p12, CN=zte-internal-client) — and the agent-runner holds it too, so
# "presents a cert signed by our CA" would let an agent call the MCP backend
# directly and skip the gate entirely. This hop therefore gets a client cert
# nothing else is given, and the bridge authorises by that CN.
info "6/7  Generating MCP bridge server + gateway-only client certificates ..."
openssl req -newkey rsa:2048 \
    -keyout mcp-bridge.key -out mcp-bridge.csr \
    -nodes -subj "${SUBJ_BASE}/CN=mcp-bridge"

openssl x509 -req \
    -in mcp-bridge.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out mcp-bridge.crt -days $DAYS_SVC \
    -extfile <(printf "subjectAltName=DNS:mcp-bridge,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth")

openssl req -newkey rsa:2048 \
    -keyout gateway-mcp-client.key -out gateway-mcp-client.csr \
    -nodes -subj "${SUBJ_BASE}/CN=zte-gateway-mcp"

openssl x509 -req \
    -in gateway-mcp-client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out gateway-mcp-client.crt -days $DAYS_SVC \
    -extfile <(printf "extendedKeyUsage=clientAuth")

openssl pkcs12 -export \
    -in gateway-mcp-client.crt -inkey gateway-mcp-client.key \
    -certfile ca.crt \
    -out gateway-mcp-client.p12 -passout "pass:${PASS}" \
    -name "zte-gateway-mcp"

# ── 7. Truststore (CA cert only) ────────────────────────────────────────────
info "7/7  Generating shared truststore (CA cert only) ..."
rm -f truststore.p12
keytool -import -trustcacerts \
    -alias zte-ca \
    -file ca.crt \
    -keystore truststore.p12 \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -noprompt

# ── Cleanup intermediate files ──────────────────────────────────────────────
rm -f ./*.csr ./*.srl

info "Done! Generated certificate files:"
ls -lh "${DIR}"/*.p12 "${DIR}"/*.crt "${DIR}"/*.key 2>/dev/null || true
echo ""
warn "IMPORTANT: Never commit *.key or *.p12 files. They are gitignored."
warn "Run this script once per dev environment or after cert expiry."
