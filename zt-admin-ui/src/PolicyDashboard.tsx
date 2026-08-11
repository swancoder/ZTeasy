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
import Tooltip from '@mui/material/Tooltip'
import type { IdpIdentityEntry, PolicyDocument, PolicyRule, ReloadResult } from './types'

interface Props {
  accessToken: string
}

type IdentityTypeName = 'USER' | 'GROUP' | 'ROLE' | 'CLIENT'

// Mirrors gateway-service's com.zte.gateway.identity.IdentityUrn.parse
// (ADR-014, client: prefix + defaultType added by ADR-015) — intentionally
// duplicated (~10 lines) rather than shared, to keep this tab's
// orphan-highlighting independent of the Identities tab's data fetch.
function parseUrn(source: string, defaultType: IdentityTypeName): { type: IdentityTypeName; name: string } | null {
  if (!source || source.includes('*') || source.includes('?')) return null
  if (source.startsWith('user:')) return { type: 'USER', name: source.slice('user:'.length) }
  if (source.startsWith('group:')) return { type: 'GROUP', name: source.slice('group:'.length) }
  if (source.startsWith('role:')) return { type: 'ROLE', name: source.slice('role:'.length) }
  if (source.startsWith('client:')) return { type: 'CLIENT', name: source.slice('client:'.length) }
  return { type: defaultType, name: source }
}

interface Category {
  key: keyof Omit<PolicyDocument, 'schemaVersion'>
  title: string
  description: string
  // What a bare (no-prefix) source implies — ROLE for users2service (ADR-014),
  // CLIENT for service2service/agentMcpToolCalls (ADR-015), since every rule
  // in those two categories predates URN sources and was already a client id.
  defaultSourceType: IdentityTypeName
}

const CATEGORIES: Category[] = [
  { key: 'users2service', title: 'users2service', description: 'Human user (realm role) → gateway REST service', defaultSourceType: 'ROLE' },
  { key: 'service2service', title: 'service2service', description: 'Calling service/agent (JWT azp) → gateway REST service', defaultSourceType: 'CLIENT' },
  { key: 'agentMcpToolCalls', title: 'agentMcpToolCalls', description: 'MCP agent (JWT azp) → MCP tool name', defaultSourceType: 'CLIENT' },
]

function RuleTable({ rules, identitySet, defaultSourceType }: { rules: PolicyRule[]; identitySet?: Set<string>; defaultSourceType: IdentityTypeName }) {
  if (rules.length === 0) {
    return (
      <Typography color="text.secondary" sx={{ p: 2 }}>
        No rules defined — falls through to the category default.
      </Typography>
    )
  }

  function isOrphaned(rule: PolicyRule): boolean {
    if (!identitySet) return false
    const urn = parseUrn(rule.source, defaultSourceType)
    if (!urn) return false
    return !identitySet.has(`${urn.type}:${urn.name}`)
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
          {rules.map((rule) => {
            const orphaned = isOrphaned(rule)
            return (
              <TableRow key={rule.id} sx={orphaned ? { bgcolor: 'warning.light' } : undefined}>
                <TableCell>{rule.id}</TableCell>
                <TableCell>
                  <Chip
                    label={rule.effect}
                    color={rule.effect === 'DENY' ? 'error' : 'success'}
                    size="small"
                  />
                </TableCell>
                <TableCell>
                  {rule.source}
                  {orphaned && (
                    <Tooltip title="No matching identity found in the synced IdP cache — check the Identities tab or run a sync">
                      <span style={{ marginLeft: 6 }}>⚠️</span>
                    </Tooltip>
                  )}
                </TableCell>
                <TableCell>{rule.target}</TableCell>
                <TableCell>{rule.pathPattern ?? '—'}</TableCell>
                <TableCell>{rule.methods ?? '—'}</TableCell>
                <TableCell>{rule.priority}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

export default function PolicyDashboard({ accessToken }: Props) {
  const [policies, setPolicies] = useState<PolicyDocument | null>(null)
  const [identitySet, setIdentitySet] = useState<Set<string>>(new Set())
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

  const fetchIdentitySet = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/admin/identities/search', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) return
      const identities: IdpIdentityEntry[] = await res.json()
      setIdentitySet(new Set(identities.map((i) => `${i.type}:${i.name}`)))
    } catch {
      // Orphan-highlighting is best-effort — a failed identity fetch just means no rows get flagged.
    }
  }, [accessToken])

  useEffect(() => {
    fetchPolicies()
    fetchIdentitySet()
  }, [fetchPolicies, fetchIdentitySet])

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
            <RuleTable
              rules={policies?.[category.key] ?? []}
              identitySet={identitySet}
              defaultSourceType={category.defaultSourceType}
            />
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
