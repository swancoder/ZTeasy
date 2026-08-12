import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Snackbar from '@mui/material/Snackbar'
import Alert from '@mui/material/Alert'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import Dialog from '@mui/material/Dialog'
import DialogTitle from '@mui/material/DialogTitle'
import DialogContent from '@mui/material/DialogContent'
import DialogActions from '@mui/material/DialogActions'
import TextField from '@mui/material/TextField'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import SchemaDrawer from './SchemaDrawer'
import type { InventoryEntry } from './types'

interface Props {
  accessToken: string
}

const STATUS_COLOR: Record<InventoryEntry['status'], 'success' | 'warning' | 'error' | 'default'> = {
  ACTIVE: 'success',
  WARNING: 'warning',
  DOWN: 'error',
  PENDING: 'default',
}

export default function Inventory({ accessToken }: Props) {
  const [services, setServices] = useState<InventoryEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [snackbar, setSnackbar] = useState<{ message: string; severity: 'success' | 'error' } | null>(null)

  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [formName, setFormName] = useState('')
  const [formTargetType, setFormTargetType] = useState<'REST' | 'MCP'>('REST')
  const [formBaseUrl, setFormBaseUrl] = useState('')
  const [formDocsUrl, setFormDocsUrl] = useState('')
  const [formManagementUrl, setFormManagementUrl] = useState('')

  const [schemaTarget, setSchemaTarget] = useState<InventoryEntry | null>(null)
  const [fetchingId, setFetchingId] = useState<string | null>(null)

  const fetchServices = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/v1/admin/inventory', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/admin/inventory -> ${res.status}`)
      }
      setServices(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    fetchServices()
  }, [fetchServices])

  const resetForm = () => {
    setFormName('')
    setFormTargetType('REST')
    setFormBaseUrl('')
    setFormDocsUrl('')
    setFormManagementUrl('')
  }

  const handleOnboard = async () => {
    setSubmitting(true)
    try {
      const res = await fetch('/api/v1/admin/inventory', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: formName,
          targetType: formTargetType,
          baseUrl: formBaseUrl,
          docsUrl: formDocsUrl || null,
          managementUrl: formManagementUrl || null,
        }),
      })
      if (res.ok) {
        setSnackbar({ message: `${formName} onboarded — discovery running in the background`, severity: 'success' })
        setDialogOpen(false)
        resetForm()
        await fetchServices()
      } else {
        const body = await res.json().catch(() => ({}))
        setSnackbar({ message: `Onboarding failed: ${body.error ?? res.status}`, severity: 'error' })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setSubmitting(false)
    }
  }

  const handleFetch = async (service: InventoryEntry) => {
    setFetchingId(service.id)
    try {
      const res = await fetch(`/api/v1/admin/inventory/${service.id}/schema/fetch`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok) {
        setSnackbar({ message: `${service.name}: schema fetched successfully`, severity: 'success' })
        await fetchServices()
      } else {
        const body = await res.json().catch(() => ({}))
        setSnackbar({ message: `${service.name}: fetch failed — ${body.error ?? res.status}`, severity: 'error' })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setFetchingId(null)
    }
  }

  const handleDelete = async (service: InventoryEntry) => {
    try {
      const res = await fetch(`/api/v1/admin/inventory/${service.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok || res.status === 204) {
        setSnackbar({ message: `${service.name} removed from the registry`, severity: 'success' })
        await fetchServices()
      } else {
        setSnackbar({ message: `Delete failed: ${res.status}`, severity: 'error' })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    }
  }

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
        <CircularProgress />
      </Box>
    )
  }

  if (error) {
    return (
      <Box sx={{ p: 4 }}>
        <Alert severity="error">{error}</Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h5">APIM Registry ({services.length})</Typography>
        <Button variant="contained" onClick={() => setDialogOpen(true)}>
          Onboard Service
        </Button>
      </Box>

      {services.length === 0 ? (
        <Typography color="text.secondary">
          No services registered yet — click &quot;Onboard Service&quot; to add one.
        </Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Base URL</TableCell>
                <TableCell>Docs URL</TableCell>
                <TableCell>Management URL</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Ping (ms)</TableCell>
                <TableCell>Last Successful Call</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {services.map((service) => (
                <TableRow key={service.id}>
                  <TableCell>{service.name}</TableCell>
                  <TableCell>
                    <Chip label={service.targetType} size="small" />
                  </TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{service.baseUrl}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{service.docsUrl ?? '—'}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                    {service.managementUrl ?? '—'}
                  </TableCell>
                  <TableCell>
                    <Chip label={service.status} color={STATUS_COLOR[service.status]} size="small" />
                  </TableCell>
                  <TableCell>{service.lastPingMs ?? '—'}</TableCell>
                  <TableCell>
                    {service.lastSuccessfulCall ? new Date(service.lastSuccessfulCall).toLocaleString() : '—'}
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Fetch schema now">
                      <span>
                        <IconButton
                          size="small"
                          disabled={fetchingId === service.id}
                          onClick={() => handleFetch(service)}
                        >
                          {fetchingId === service.id ? <CircularProgress size={16} /> : '🔄'}
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title={service.hasSchema ? 'View discovered schema' : 'No schema captured yet — try Fetch'}>
                      <span>
                        <IconButton size="small" disabled={!service.hasSchema} onClick={() => setSchemaTarget(service)}>
                          📄
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title="Remove from registry">
                      <IconButton size="small" onClick={() => handleDelete(service)}>
                        🗑️
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Onboard Service</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Name"
              placeholder="e.g. hubspot-mcp"
              fullWidth
              value={formName}
              onChange={(e) => setFormName(e.target.value)}
            />
            <TextField
              select
              label="Type"
              fullWidth
              value={formTargetType}
              onChange={(e) => setFormTargetType(e.target.value as 'REST' | 'MCP')}
            >
              <MenuItem value="REST">REST</MenuItem>
              <MenuItem value="MCP">MCP</MenuItem>
            </TextField>
            <TextField
              label="Base URL"
              placeholder="https://example.com"
              fullWidth
              value={formBaseUrl}
              onChange={(e) => setFormBaseUrl(e.target.value)}
            />
            <TextField
              label="Docs URL (optional, REST only)"
              placeholder="e.g. https://example.com/openapi.json — only if OpenAPI docs aren't at /v3/api-docs"
              helperText="Leave blank to probe {Base URL}/v3/api-docs by default"
              fullWidth
              value={formDocsUrl}
              onChange={(e) => setFormDocsUrl(e.target.value)}
            />
            <TextField
              label="Management URL (optional)"
              placeholder="e.g. http://localhost:9081 — only if /actuator/health lives elsewhere"
              helperText="Leave blank if the service's health endpoint is at the Base URL above"
              fullWidth
              value={formManagementUrl}
              onChange={(e) => setFormManagementUrl(e.target.value)}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="contained"
            onClick={handleOnboard}
            disabled={submitting || !formName || !formBaseUrl}
          >
            {submitting ? 'Onboarding…' : 'Onboard'}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={5000}
        onClose={() => setSnackbar(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {snackbar ? <Alert severity={snackbar.severity}>{snackbar.message}</Alert> : undefined}
      </Snackbar>

      <SchemaDrawer service={schemaTarget} accessToken={accessToken} onClose={() => setSchemaTarget(null)} />
    </Box>
  )
}
