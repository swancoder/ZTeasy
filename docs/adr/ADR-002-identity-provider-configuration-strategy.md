# ADR-002: Identity Provider Configuration Strategy

**Status:** Accepted
**Date:** 2026-04-20
**Deciders:** ZTE-Lightweight Architects

---

## Context

The ZTE-Lightweight project requires a Keycloak realm (`zte-realm`) with:
- A confidential OIDC client (`zte-gateway`) for the API Gateway
- Realm roles (`ADMIN`, `USER`) for Zero Trust access control
- At least one test user (`zte-admin`) assigned the `ADMIN` role

This realm configuration must be:
1. Reproducible: any developer should get an identical Keycloak state with a single `docker compose up`
2. Version-controlled: the realm definition lives in the repository, not in a manual UI workflow
3. Minimal-dependency: no additional toolchain beyond Docker Compose for local dev

Three automation strategies were evaluated.

---

## Decision

**We will use Keycloak's native `realm-export.json` with the `--import-realm` startup flag.**

A `keycloak/realm-export.json` file defines the complete realm structure. It is mounted as a read-only volume into `/opt/keycloak/data/import/` in the Keycloak container. Keycloak imports it automatically on first startup via the `start-dev --import-realm` command.

A helper script (`scripts/set-keycloak-password.sh`) sets the `zte-admin` password post-startup via `kcadm.sh`, since Keycloak 24 does not accept plaintext credentials in import files.

---

## Alternatives Considered

### Option A: Keycloak Config CLI (codecentric/keycloak-config-cli)
- **Pros:** Supports declarative YAML/JSON with variable substitution; idempotent apply (merge/diff); multi-environment (dev/staging/prod) with a single config file.
- **Cons:** Requires an extra Docker init-container in `docker-compose.yml`; external toolchain dependency; significantly more complex Compose setup; no meaningful benefit over native import at single-environment MVP scale.
- **Verdict:** Rejected for MVP. Revisit when promoting to staging/prod with multi-env config needs.

### Option B: Terraform + Keycloak Provider (`mrparkers/terraform-provider-keycloak`)
- **Pros:** Infrastructure-as-Code with state management; integrates with CI/CD pipelines; production-grade drift detection; fits a broader IaC story (Postgres, Keycloak, gateway config all in one Terraform plan).
- **Cons:** Requires Terraform binary, remote/local state management, and Keycloak to be running before `terraform apply` can execute (chicken-and-egg problem for local dev bootstrap). Significant overhead for a single-realm dev environment. Team expertise gap.
- **Verdict:** Rejected for MVP. Correct choice for production IaC when moving to Kubernetes. Log as a future migration path.

### Option C: Native `realm-export.json` + `--import-realm` (Selected)
- **Pros:** Zero external dependencies — built into Keycloak. Runs at container start. Works cleanly with `dev-file` (H2) mode: `docker compose down` clears container-local H2 data → clean reimport on next `up`. Realm definition is a single JSON file checked into git. Minimal Compose changes (one `command` flag + one `volume` mount).
- **Cons:** Import is skipped if realm already exists (requires `down`, not just `restart`, to reset). PBKDF2-SHA256 user credentials must be pre-hashed — plaintext not accepted — so initial user password is set separately via `kcadm.sh`. No multi-env variable substitution in JSON (hardcoded values only).
- **Verdict:** Accepted for MVP.

---

## Chain of Thought (CoT)

1. **Zero extra tooling.** Adding a Keycloak Config CLI init-container means every developer needs Docker images pulled for a secondary tool. The native import mechanism achieves identical results with zero additions to the stack.

2. **Dev-file H2 is intentionally ephemeral.** Keycloak runs with `KC_DB: dev-file`. The H2 data lives in the container filesystem — not in a named volume. Every `docker compose down` discards it. The import runs fresh on every `up`. This eliminates "stale realm" state issues during development.

3. **Hashed credentials are brittle; kcadm.sh is reliable.** Keycloak 24 requires PBKDF2-SHA256 hashed credentials in import JSON. Pre-computing a correct hash without running Keycloak is error-prone. A silently wrong hash results in an unusable user with no error. Using `kcadm.sh` to set the password post-startup is explicit, verifiable, and idiomatic (it's Keycloak's own CLI tool).

4. **Client secret in JSON is acceptable dev-only.** The `zte-gateway` OIDC client's secret was hardcoded in `realm-export.json` and mirrored in `application.yml` as a default. (**Reversed by ADR-037**: the tracked realm is now a template carrying placeholders, the defaults are gone, and the value that used to sit here has been rotated.) This is never acceptable in staging or production — production must inject via `KEYCLOAK_CLIENT_SECRET` environment variable from a secret manager.

5. **Realm name `zte-realm` (not `zte`).** The explicit realm name avoids ambiguity with Keycloak's internal `master` realm and follows the `kebab-case` naming convention defined in CLAUDE.md. All URIs in `application.yml` and `docker-compose.yml` are updated to `realms/zte-realm`.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| `--import-realm` skips existing realm on container restart | Medium | Documented. Reset with `docker compose down && docker compose up -d`. Dev-file H2 is container-local so this always produces clean state. |
| User `zte-admin` has no password after import | Medium | `scripts/set-keycloak-password.sh` must be run once after first `docker compose up`. README/onboarding docs must highlight this step. |
| The gateway client secret is hardcoded in git (closed by ADR-037) | High (prod) | Marked as dev-only. Production deployment must override via environment variable / secret manager. Scope limited to local dev branch. |
| No multi-env support in plain JSON | Low (now) | Acceptable for single dev environment. Keycloak Config CLI provides variable substitution when multi-env is needed. |
| Terraform not adopted for Keycloak | Low (now) | Logged as future migration path. When Kubernetes + production Keycloak are adopted, Terraform module for Keycloak is the correct IaC approach. |

---

## Consequences

- **Positive:** A single `docker compose up -d` bootstraps a complete, correctly-configured Keycloak realm with no manual steps (except the one-time password set).
- **Positive:** Realm configuration is version-controlled, reviewable, and reproducible across developer machines.
- **Positive:** `application.yml` defaults point to `zte-realm` — the Gateway connects to Keycloak with no additional env configuration in local dev.
- **Negative:** Initial password setup requires a separate manual step (`set-keycloak-password.sh`). Automated testing pipelines must incorporate this or use `directAccessGrantsEnabled` client credentials flow.
- **Negative:** Realm JSON changes require `docker compose down` (not just `restart`) to take effect — a subtle footgun for developers unfamiliar with the dev-file lifecycle.

---

## Future Migration Path

- **Multi-env (staging/prod):** Replace `realm-export.json` with Keycloak Config CLI YAML for variable substitution (`CLIENT_SECRET`, `REALM_NAME`, etc.) across environments.
- **Production IaC:** Adopt `mrparkers/terraform-provider-keycloak` as part of the full infrastructure Terraform plan (alongside PostgreSQL RDS, EKS, etc.).
- **mTLS:** When the service mesh (Istio) is adopted for east-west traffic, integrate Keycloak's X.509 authenticator for service accounts, removing the need for client secrets.
