import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CircularProgress from '@mui/material/CircularProgress'
import Alert from '@mui/material/Alert'
import SwaggerUI from 'swagger-ui-react'
import 'swagger-ui-react/swagger-ui.css'

// Unlike every other tab, this one takes no accessToken — /v3/api-docs is
// permitAll (ApiDocsSecurityConfig, Stage 25 / ADR-025): it's a route-shape
// spec, not data, so the fetch below needs no Authorization header.
export default function Documentation() {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [spec, setSpec] = useState<object | null>(null)

  useEffect(() => {
    fetch('/v3/api-docs')
      .then((res) => {
        if (!res.ok) {
          throw new Error(`GET /v3/api-docs -> ${res.status}`)
        }
        return res.json()
      })
      .then(setSpec)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }, [])

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ mb: 2 }}>
        Gateway API Reference
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Auto-generated from gateway-service's own REST controllers (springdoc-openapi). Covers
        /api/v1/admin/** and /api/v1/internal/**, not the proxy routes to service-a/service-b or
        the MCP tool surface (see the Registry tab for those).
      </Typography>

      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
          <CircularProgress />
        </Box>
      )}

      {error && <Alert severity="error">{error}</Alert>}

      {!loading && !error && spec && <SwaggerUI spec={spec} />}
    </Box>
  )
}
