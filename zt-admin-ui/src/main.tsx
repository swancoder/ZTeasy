import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import CssBaseline from '@mui/material/CssBaseline'
import App from './App'
import './index.css'

// Keycloak client "zte-admin-ui" (ADR-012) — public client, authorization
// code + PKCE. redirect_uri must exactly match a Valid Redirect URI on that
// client (keycloak/realm-export.json: http://localhost:8080/admin/*). Points
// at the exact served file (not a bare "/admin/" directory path) — Spring
// Boot's static-resource serving only auto-resolves index.html at the
// context root, not nested paths.
const oidcConfig = {
  authority: 'http://localhost:8180/realms/zte-realm',
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
      <CssBaseline />
      <App />
    </AuthProvider>
  </StrictMode>,
)
