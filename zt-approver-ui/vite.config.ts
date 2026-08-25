import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Served by the gateway at https://localhost:8080/approver/ (ADR-026) — every
  // emitted asset URL must be prefixed accordingly. Mirrors zt-admin-ui's
  // /admin/ base; the two SPAs are deliberately separate bundles (different
  // Keycloak clients, different audience — see the ADR).
  base: '/approver/',
})
