import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import CssBaseline from '@mui/material/CssBaseline'
import { ThemeProvider } from '@mui/material/styles'
import theme from './theme'
import App from './App'
import './index.css'

// Keycloak client "zte-admin-ui" (ADR-012) — public client, authorization
// code + PKCE. redirect_uri must exactly match a Valid Redirect URI on that
// client (keycloak/realm-export.json: http://localhost:8080/admin/*). Points
// at the exact served file (not a bare "/admin/" directory path) — Spring
// Boot's static-resource serving only auto-resolves index.html at the
// context root, not nested paths.
// The authority comes from the gateway-served /ui-config.js (ADR-026): the
// same built bundle works whether Keycloak is reached directly
// (http://localhost:8180, local dev — the fallback below) or reverse-proxied
// under the gateway's own origin (/auth, Azure deployment — ADR-027).
declare global {
  interface Window {
    ZTE_OIDC_AUTHORITY?: string
  }
}

const oidcConfig = {
  authority: window.ZTE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/zte-realm',
  client_id: 'zte-admin-ui',
  redirect_uri: `${window.location.origin}/admin/index.html`,
  response_type: 'code',
  scope: 'openid profile email',
  // Strip the ?code=...&state=... query string the Keycloak redirect leaves
  // behind, so a page refresh doesn't try to re-process a spent auth code.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname)
  },
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <ThemeProvider theme={theme}>
      <CssBaseline />
      <App />
      </ThemeProvider>
    </AuthProvider>
  </StrictMode>,
)
