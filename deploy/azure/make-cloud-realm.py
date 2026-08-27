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

ADMIN_PASSWORD = os.environ.get("ZTE_ADMIN_PASSWORD", "Admin@123!")
USER_PASSWORD = os.environ.get("ZTE_USER_PASSWORD", "User@123!")

BROWSER_CLIENT_PATHS = {
    "zte-gateway": "/*",
    "zte-admin-ui": "/admin/*",
    "zte-approver-ui": "/approver/*",
}

# The executive-dashboard audiences (ADR-029) need working logins in the
# cloud too — same demo-grade posture as the two users above, since the
# realm is re-imported from this file on every Keycloak start.
DEMO_PASSWORD = os.environ.get("ZTE_DEMO_PASSWORD", "Demo@123!")

PASSWORDS = {
    "zte-admin": ADMIN_PASSWORD,
    "zte-test-user": USER_PASSWORD,
    "zte-ceo": DEMO_PASSWORD,
    "zte-cfo": DEMO_PASSWORD,
    "zte-cto": DEMO_PASSWORD,
    "zte-board": DEMO_PASSWORD,
    "zte-dpo": DEMO_PASSWORD,
}


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    # Several origins may be given, comma-separated: the deployment serves the
    # SPAs from both the custom domain (browser-facing app) and the Azure FQDN
    # (agent-facing app, ADR-028), and a browser can legitimately arrive at
    # either. The issuer stays single — that's KC_HOSTNAME_URL, not this file.
    origins = [o.rstrip("/") for o in sys.argv[1].split(",") if o.strip()]
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "out", "realm-cloud.json")

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

    for user in realm.get("users", []):
        password = PASSWORDS.get(user.get("username"))
        if password is not None:
            user["credentials"] = [{"type": "password", "value": password, "temporary": False}]

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(realm, f, indent=2)
    print(f"wrote {out_path} (origins={', '.join(origins)})")


if __name__ == "__main__":
    main()
