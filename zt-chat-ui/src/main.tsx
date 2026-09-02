import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import CssBaseline from '@mui/material/CssBaseline'
import { ThemeProvider } from '@mui/material/styles'
import theme from './theme'
import App from './App'
import './index.css'

// Keycloak client "zte-chat-ui" (ADR-039) — its own public client, so a chat
// login is a different thing from an Admin Console login in Keycloak, in the
// audit trail, and in policy: a rule can require that a person arrived through
// this front door specifically.
//
// The authority comes from the gateway-served /ui-config.js (ADR-027): the
// same built bundle works whether Keycloak is reached directly
// (http://localhost:8180, local dev) or reverse-proxied under the gateway's
// own origin (/auth, Azure deployment).
declare global {
  interface Window {
    ZTE_OIDC_AUTHORITY?: string
  }
}

const oidcConfig = {
  authority: window.ZTE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/zte-realm',
  client_id: 'zte-chat-ui',
  redirect_uri: `${window.location.origin}/chat/index.html`,
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
