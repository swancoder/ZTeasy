#!/usr/bin/env python3
"""Generates the cloud variant of keycloak/realm-export.json (ADR-027).

Differences from the base (local-dev) realm file:
  1. User passwords are baked in as import credentials — there is no
     `docker exec kcadm.sh` equivalent against a managed container platform,
     and Keycloak's dev-file H2 store is ephemeral there anyway (every
     restart re-imports the realm). Demo-grade by design, same passwords
     scripts/set-keycloak-password.sh would set locally.
  2. Browser-client redirect URIs / web origins are rewritten to the single
     external origin (the gateway's), since Keycloak is reverse-proxied
     under {origin}/auth and the SPAs live on that same origin.

Usage: make-cloud-realm.py <external-origin> [out-file]
   e.g. make-cloud-realm.py https://localhost:8443
        make-cloud-realm.py https://zte-gateway.<env>.westeurope.azurecontainerapps.io:8443
"""

import json
import os
import sys

# Credentials come from the environment, never from this file. The cloud
# values live in deploy/azure/out/cloud-credentials.env (gitignored) — source
# it before running this script. A missing variable is a hard error rather
# than a default: this repository is public, and a committed fallback is
# exactly how demo passwords end up guarding a live deployment.
def required(var: str) -> str:
    value = os.environ.get(var)
    if not value:
        raise SystemExit(
            f"{var} is not set. Source deploy/azure/out/cloud-credentials.env "
            "before generating the cloud realm."
        )
    return value


BROWSER_CLIENT_PATHS = {
    "zte-gateway": "/*",
    "zte-admin-ui": "/admin/*",
    "zte-approver-ui": "/approver/*",
}

# username -> env var holding its password
USER_PASSWORD_VARS = {
    "zte-admin": "ZTE_PW_ZTE_ADMIN",
    "zte-test-user": "ZTE_PW_ZTE_TEST_USER",
    "zte-ceo": "ZTE_PW_ZTE_CEO",
    "zte-cfo": "ZTE_PW_ZTE_CFO",
    "zte-cto": "ZTE_PW_ZTE_CTO",
    "zte-board": "ZTE_PW_ZTE_BOARD",
    "zte-dpo": "ZTE_PW_ZTE_DPO",
}

# clientId -> env var holding its secret. realm-export.json keeps obvious
# "-dev-only" secrets so a localhost clone still runs out of the box; the
# cloud must not reuse them, since anyone can read them.
CLIENT_SECRET_VARS = {
    "zte-gateway": "ZTE_SECRET_ZTE_GATEWAY",
    "agent-a": "ZTE_SECRET_AGENT_A",
    "agent-b": "ZTE_SECRET_AGENT_B",
    "service-a": "ZTE_SECRET_SERVICE_A",
    "crm-account-health-emea-01": "ZTE_SECRET_CRM_ACCOUNT_HEALTH_EMEA_01",
}


def main() -> None:
    # --local: the same substitution for a developer machine (ADR-037). The tracked
    # realm is a template carrying placeholders instead of client secrets, so SOME
    # generator has to run before Keycloak can start — cloud and localhost may as
    # well use the one that is already tested, rather than two that can drift.
    if "--local" in sys.argv:
        argv = [a for a in sys.argv if a != "--local"]
        origin = argv[1] if len(argv) > 1 else "http://localhost:8080"
        out = os.path.join(os.path.dirname(__file__), "..", "..", "keycloak", "local", "realm.json")
        generate([origin.rstrip("/")], os.path.abspath(out))
        return
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    # Several origins may be given, comma-separated: the deployment serves the
    # SPAs from both the custom domain (browser-facing app) and the Azure FQDN
    # (agent-facing app, ADR-028), and a browser can legitimately arrive at
    # either. The issuer stays single — that's KC_HOSTNAME_URL, not this file.
    origins = [o.rstrip("/") for o in sys.argv[1].split(",") if o.strip()]
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "out", "realm-cloud.json")
    generate(origins, out_path)


def generate(origins: "list[str]", out_path: str) -> None:
    base_path = os.path.join(os.path.dirname(__file__), "..", "..", "keycloak", "realm-export.json")
    with open(base_path) as f:
        realm = json.load(f)

    for client in realm.get("clients", []):
        path = BROWSER_CLIENT_PATHS.get(client["clientId"])
        if path is not None:
            client["redirectUris"] = [f"{origin}{path}" for origin in origins]
            client["webOrigins"] = list(origins)
        # Keycloak's CLIENT.DESCRIPTION column is VARCHAR(255); the H2
        # dev-file import hard-fails on longer values (seen live with the
        # crm-account-health client's 271-char description).
        if len(client.get("description") or "") > 255:
            client["description"] = client["description"][:252] + "..."
        secret_var = CLIENT_SECRET_VARS.get(client["clientId"])
        if secret_var is not None and client.get("secret"):
            client["secret"] = required(secret_var)

    for user in realm.get("users", []):
        password_var = USER_PASSWORD_VARS.get(user.get("username"))
        if password_var is not None:
            user["credentials"] = [
                {"type": "password", "value": required(password_var), "temporary": False}
            ]

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(realm, f, indent=2)
    print(f"wrote {out_path} (origins={', '.join(origins)})")


if __name__ == "__main__":
    main()
