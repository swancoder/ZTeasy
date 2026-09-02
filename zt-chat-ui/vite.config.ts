import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Served by the gateway at /chat/ (ADR-039). A third separate bundle with its
  // own Keycloak client and its own realm role: the chat console is meant to be
  // reachable by people who have no business in the Admin Console or the Approval
  // Center, and separating them by identity is what makes that true.
  base: '/chat/',
})
