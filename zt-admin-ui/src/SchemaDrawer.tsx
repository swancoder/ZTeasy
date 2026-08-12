import { useEffect, useState } from 'react'
import Drawer from '@mui/material/Drawer'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CircularProgress from '@mui/material/CircularProgress'
import Alert from '@mui/material/Alert'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import SwaggerUI from 'swagger-ui-react'
import 'swagger-ui-react/swagger-ui.css'
import type { InventoryEntry } from './types'

interface Props {
  service: InventoryEntry | null
  accessToken: string
  onClose: () => void
}

interface McpTool {
  name: string
  description?: string
}

// Discovered schema is fetched on demand (ADR-016 amendment) — the registry
// list view never carries this payload, so nothing happens here until a row's
// "View Schema" button opens this drawer.
export default function SchemaDrawer({ service, accessToken, onClose }: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rawSchema, setRawSchema] = useState<string | null>(null)

  useEffect(() => {
    if (!service) {
      setRawSchema(null)
      setError(null)
      return
    }
    setLoading(true)
    setError(null)
    setRawSchema(null)

    fetch(`/api/v1/admin/inventory/${service.id}/schema`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then(async (res) => {
        if (res.status === 404) {
          throw new Error('No schema captured yet — discovery may still be running, or the last probe failed.')
        }
        if (!res.ok) {
          throw new Error(`GET .../schema -> ${res.status}`)
        }
        return res.text()
      })
      .then(setRawSchema)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }, [service, accessToken])

  return (
    <Drawer
      anchor="right"
      open={service !== null}
      onClose={onClose}
      sx={{ '& .MuiDrawer-paper': { width: '70vw', maxWidth: 1100 } }}
    >
      <Box sx={{ p: 3, height: '100%', overflow: 'auto' }}>
        <Typography variant="h6" sx={{ mb: 2 }}>
          {service?.name} — Discovered Schema
        </Typography>

        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
            <CircularProgress />
          </Box>
        )}

        {error && <Alert severity="warning">{error}</Alert>}

        {!loading && !error && rawSchema && service && <SchemaBody service={service} rawSchema={rawSchema} />}
      </Box>
    </Drawer>
  )
}

function SchemaBody({ service, rawSchema }: { service: InventoryEntry; rawSchema: string }) {
  let parsed: unknown
  try {
    parsed = JSON.parse(rawSchema)
  } catch {
    return (
      <Box>
        <Alert severity="error" sx={{ mb: 2 }}>
          Captured schema isn&apos;t valid JSON — showing the raw text below.
        </Alert>
        <Box component="pre" sx={{ whiteSpace: 'pre-wrap', fontSize: '0.8rem', fontFamily: 'monospace' }}>
          {rawSchema}
        </Box>
      </Box>
    )
  }

  if (service.targetType === 'REST') {
    return <SwaggerUI spec={parsed as object} />
  }

  return <McpToolsList parsed={parsed} />
}

function McpToolsList({ parsed }: { parsed: unknown }) {
  const tools = extractTools(parsed)

  if (tools.length === 0) {
    return <Alert severity="info">No tools found in the captured {'tools/list'} response.</Alert>
  }

  return (
    <List>
      {tools.map((tool, i) => (
        <ListItem key={`${tool.name}-${i}`} divider>
          <ListItemText primary={tool.name} secondary={tool.description ?? 'No description provided'} />
        </ListItem>
      ))}
    </List>
  )
}

// Walks the captured JSON-RPC tools/list response ({ result: { tools: [...] } })
// defensively — this is an external service's response, not our own schema.
function extractTools(parsed: unknown): McpTool[] {
  if (typeof parsed !== 'object' || parsed === null) return []
  const result = (parsed as Record<string, unknown>).result
  if (typeof result !== 'object' || result === null) return []
  const tools = (result as Record<string, unknown>).tools
  if (!Array.isArray(tools)) return []
  return tools.filter(
    (t): t is McpTool => typeof t === 'object' && t !== null && typeof (t as Record<string, unknown>).name === 'string',
  )
}
