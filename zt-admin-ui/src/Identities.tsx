import { useCallback, useEffect, useMemo, useState } from 'react'
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
import Typography from '@mui/material/Typography'
import TextField from '@mui/material/TextField'
import Stack from '@mui/material/Stack'
import Accordion from '@mui/material/Accordion'
import AccordionSummary from '@mui/material/AccordionSummary'
import AccordionDetails from '@mui/material/AccordionDetails'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Drawer from '@mui/material/Drawer'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import type { IdpIdentityEntry, RelatedIdentity } from './types'

interface Props {
  accessToken: string
}

type IdentityTypeName = IdpIdentityEntry['type']

// "Actors" initiate access (Users, Clients); "Access Containers" grant it
// (Groups, Roles) — the split the task asked for. Plain string labels, not
// @mui/icons-material — this codebase has consistently avoided that
// dependency (PolicyDashboard's orphan warning uses an emoji for the same
// reason), so the accordion expand marker and info button below do too.
const ACTOR_TYPES: IdentityTypeName[] = ['USER', 'CLIENT']
const CONTAINER_TYPES: IdentityTypeName[] = ['GROUP', 'ROLE']

const TYPE_LABELS: Record<IdentityTypeName, string> = {
  USER: 'Users',
  CLIENT: 'Clients',
  GROUP: 'Groups',
  ROLE: 'Roles',
}

function IdentityAccordion({
  type,
  identities,
  onInfoClick,
}: {
  type: IdentityTypeName
  identities: IdpIdentityEntry[]
  onInfoClick?: (identity: IdpIdentityEntry) => void
}) {
  return (
    <Accordion defaultExpanded={identities.length > 0}>
      <AccordionSummary sx={{ '& .MuiAccordionSummary-content': { alignItems: 'center', gap: 1 } }}>
        <span>▾</span>
        <Typography variant="subtitle1">
          {TYPE_LABELS[type]} ({identities.length})
        </Typography>
      </AccordionSummary>
      <AccordionDetails sx={{ p: 0 }}>
        {identities.length === 0 ? (
          <Typography color="text.secondary" sx={{ p: 2 }}>
            No {TYPE_LABELS[type].toLowerCase()} match the current filter.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Name</TableCell>
                  <TableCell>Display Name</TableCell>
                  <TableCell>Last Synced</TableCell>
                  {onInfoClick && <TableCell align="right">Relations</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {identities.map((identity) => (
                  <TableRow key={identity.id}>
                    <TableCell>{identity.name}</TableCell>
                    <TableCell>{identity.displayName ?? '—'}</TableCell>
                    <TableCell>{new Date(identity.lastSynced).toLocaleString()}</TableCell>
                    {onInfoClick && (
                      <TableCell align="right">
                        <Tooltip title="Show related roles/groups">
                          <IconButton size="small" onClick={() => onInfoClick(identity)}>
                            ℹ️
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </AccordionDetails>
    </Accordion>
  )
}

function RelationsDrawer({
  accessToken,
  identity,
  onClose,
}: {
  accessToken: string
  identity: IdpIdentityEntry | null
  onClose: () => void
}) {
  const [relations, setRelations] = useState<RelatedIdentity[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!identity) return
    setLoading(true)
    setError(null)
    fetch(`/api/v1/admin/identities/${identity.id}/relations`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then((res) => {
        if (!res.ok) throw new Error(`GET .../relations -> ${res.status}`)
        return res.json()
      })
      .then(setRelations)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }, [identity, accessToken])

  const roles = relations.filter((r) => r.relationType === 'HAS_ROLE')
  const groups = relations.filter((r) => r.relationType === 'MEMBER_OF')

  return (
    <Drawer anchor="right" open={identity !== null} onClose={onClose}>
      <Box sx={{ width: 360, p: 3 }}>
        <Typography variant="h6" gutterBottom>
          {identity?.name}
        </Typography>
        <Typography variant="body2" color="text.secondary" gutterBottom>
          {identity ? TYPE_LABELS[identity.type] : ''} — cached relations only, no live Keycloak lookup
        </Typography>

        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
            <CircularProgress size={24} />
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}

        {!loading && !error && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 3 }}>
              Roles ({roles.length})
            </Typography>
            {roles.length === 0 ? (
              <Typography color="text.secondary" variant="body2">
                None
              </Typography>
            ) : (
              <List dense>
                {roles.map((r) => (
                  <ListItem key={r.id} disableGutters>
                    <ListItemText primary={r.name} secondary={r.displayName} />
                  </ListItem>
                ))}
              </List>
            )}

            <Typography variant="subtitle2" sx={{ mt: 3 }}>
              Groups ({groups.length})
            </Typography>
            {groups.length === 0 ? (
              <Typography color="text.secondary" variant="body2">
                None
              </Typography>
            ) : (
              <List dense>
                {groups.map((g) => (
                  <ListItem key={g.id} disableGutters>
                    <ListItemText primary={g.name} secondary={g.displayName} />
                  </ListItem>
                ))}
              </List>
            )}
          </>
        )}
      </Box>
    </Drawer>
  )
}

export default function Identities({ accessToken }: Props) {
  const [identities, setIdentities] = useState<IdpIdentityEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<IdpIdentityEntry | null>(null)
  const [snackbar, setSnackbar] = useState<{ message: string; severity: 'success' | 'error' } | null>(null)

  const fetchIdentities = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/v1/admin/identities/search', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/admin/identities/search -> ${res.status}`)
      }
      setIdentities(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    fetchIdentities()
  }, [fetchIdentities])

  const handleSync = async () => {
    setSyncing(true)
    try {
      const res = await fetch('/api/v1/admin/identities/sync', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      const body = await res.json()
      if (res.ok) {
        setSnackbar({ message: `Sync complete: ${body.synced} identities upserted`, severity: 'success' })
        await fetchIdentities()
      } else {
        setSnackbar({ message: `Sync failed: ${body.error ?? 'unknown error'}`, severity: 'error' })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setSyncing(false)
    }
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? identities.filter((i) => i.name.toLowerCase().includes(q)) : identities
  }, [identities, query])

  const byType = useMemo(() => {
    const grouped: Record<IdentityTypeName, IdpIdentityEntry[]> = { USER: [], CLIENT: [], GROUP: [], ROLE: [] }
    for (const identity of filtered) grouped[identity.type].push(identity)
    return grouped
  }, [filtered])

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
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h5">Synced IdP Identities ({identities.length})</Typography>
        <Button variant="contained" onClick={handleSync} disabled={syncing}>
          {syncing ? 'Syncing…' : 'Sync Now'}
        </Button>
      </Box>

      <TextField
        label="Quick search"
        placeholder="Filter by name…"
        size="small"
        fullWidth
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        sx={{ mb: 3 }}
      />

      {identities.length === 0 ? (
        <Typography color="text.secondary">
          No identities synced yet — click &quot;Sync Now&quot; to pull users/groups/roles/clients from the IdP.
        </Typography>
      ) : (
        <>
          <Typography variant="h6" sx={{ mb: 1 }}>
            Actors
          </Typography>
          <Stack spacing={1} sx={{ mb: 4 }}>
            {ACTOR_TYPES.map((type) => (
              <IdentityAccordion key={type} type={type} identities={byType[type]} onInfoClick={setSelected} />
            ))}
          </Stack>

          <Typography variant="h6" sx={{ mb: 1 }}>
            Access Containers
          </Typography>
          <Stack spacing={1}>
            {CONTAINER_TYPES.map((type) => (
              <IdentityAccordion key={type} type={type} identities={byType[type]} />
            ))}
          </Stack>
        </>
      )}

      <RelationsDrawer accessToken={accessToken} identity={selected} onClose={() => setSelected(null)} />

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
