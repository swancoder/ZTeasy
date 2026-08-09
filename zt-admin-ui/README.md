# zt-admin-ui

React/Vite/TypeScript admin console for ZTeasy (ADR-012). Displays the
active YAML policy set (`users2service`/`service2service`/`agentMcpToolCalls`)
and triggers a no-downtime reload. Authenticates against Keycloak
(`zte-admin-ui` client, PKCE) via `react-oidc-context`.

Not built/run standalone in normal development — `gateway-service`'s Gradle
build runs `npm install && npm run build` here automatically and packages the
output into its own jar, served at `http://localhost:8080/admin/`. See the
root `README.md`'s "Admin Console" section.

For local iteration on the UI itself:

```bash
npm install
npm run dev      # Vite dev server — note: talks to whatever gateway/Keycloak
                  # you have running at the hardcoded localhost ports in main.tsx
npm run build     # production build -> dist/
```
