import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Served by the gateway at http://localhost:8080/admin/ (ADR-012) — every
  // emitted asset URL must be prefixed accordingly.
  base: '/admin/',
})
