import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Paper from '@mui/material/Paper'
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
import Stack from '@mui/material/Stack'
import type { PolicyDocument, PolicyRule, ReloadResult } from './types'

interface Props {
  accessToken: string
}

interface Category {
  key: keyof Omit<PolicyDocument, 'schemaVersion'>
  title: string
  description: string
}

const CATEGORIES: Category[] = [
  { key: 'users2service', title: 'users2service', description: 'Human user (realm role) → gateway REST service' },
  { key: 'service2service', title: 'service2service', description: 'Calling service/agent (JWT azp) → gateway REST service' },
  { key: 'agentMcpToolCalls', title: 'agentMcpToolCalls', description: 'MCP agent (JWT azp) → MCP tool name' },
]

function RuleTable({ rules }: { rules: PolicyRule[] }) {
  if (rules.length === 0) {
    return (
      <Typography color="text.secondary" sx={{ p: 2 }}>
        No rules defined — falls through to the category default.
      </Typography>
    )
  }
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>ID</TableCell>
            <TableCell>Effect</TableCell>
            <TableCell>Source</TableCell>
            <TableCell>Target</TableCell>
            <TableCell>Path</TableCell>
            <TableCell>Methods</TableCell>
            <TableCell>Priority</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rules.map((rule) => (
            <TableRow key={rule.id}>
              <TableCell>{rule.id}</TableCell>
              <TableCell>
                <Chip
                  label={rule.effect}
                  color={rule.effect === 'DENY' ? 'error' : 'success'}
                  size="small"
                />
              </TableCell>
              <TableCell>{rule.source}</TableCell>
              <TableCell>{rule.target}</TableCell>
              <TableCell>{rule.pathPattern ?? '—'}</TableCell>
              <TableCell>{rule.methods ?? '—'}</TableCell>
              <TableCell>{rule.priority}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

export default function PolicyDashboard({ accessToken }: Props) {
  const [policies, setPolicies] = useState<PolicyDocument | null>(null)
  const [loading, setLoading] = useState(true)
  const [reloading, setReloading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [snackbar, setSnackbar] = useState<{ message: string; severity: 'success' | 'error' } | null>(null)

  const fetchPolicies = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/v1/admin/policies', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/admin/policies -> ${res.status}`)
      }
      setPolicies(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    fetchPolicies()
  }, [fetchPolicies])

  const handleReload = async () => {
    setReloading(true)
    try {
      const res = await fetch('/api/v1/admin/policies/reload', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      const body: ReloadResult = await res.json()
      if (res.ok && body.status === 'success') {
        setSnackbar({ message: `Policies reloaded at ${body.timestamp}`, severity: 'success' })
        await fetchPolicies()
      } else {
        setSnackbar({
          message: `Reload failed: ${(body.errors ?? []).join('; ') || 'unknown error'}`,
          severity: 'error',
        })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setReloading(false)
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
        <Typography variant="h5">Active YAML Policy Set (schema v{policies?.schemaVersion})</Typography>
        <Button variant="contained" onClick={handleReload} disabled={reloading}>
          {reloading ? 'Reloading…' : 'Reload Policies'}
        </Button>
      </Box>

      <Stack spacing={3}>
        {CATEGORIES.map((category) => (
          <Paper key={category.key} variant="outlined">
            <Box sx={{ p: 2, pb: 0 }}>
              <Typography variant="h6">{category.title}</Typography>
              <Typography variant="body2" color="text.secondary">
                {category.description}
              </Typography>
            </Box>
            <RuleTable rules={policies?.[category.key] ?? []} />
          </Paper>
        ))}
      </Stack>

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={5000}
        onClose={() => setSnackbar(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {snackbar ? <Alert severity={snackbar.severity}>{snackbar.message}</Alert> : undefined}
      </Snackbar>
    </Box>
  )
}
