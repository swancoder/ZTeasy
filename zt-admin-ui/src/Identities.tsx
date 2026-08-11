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
import type { IdpIdentityEntry } from './types'

interface Props {
  accessToken: string
}

export default function Identities({ accessToken }: Props) {
  const [identities, setIdentities] = useState<IdpIdentityEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
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
        <Typography variant="h5">Synced IdP Identities ({identities.length})</Typography>
        <Button variant="contained" onClick={handleSync} disabled={syncing}>
          {syncing ? 'Syncing…' : 'Sync Now'}
        </Button>
      </Box>

      {identities.length === 0 ? (
        <Typography color="text.secondary">
          No identities synced yet — click &quot;Sync Now&quot; to pull users/groups/roles from the IdP.
        </Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Display Name</TableCell>
                <TableCell>Last Synced</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {identities.map((identity) => (
                <TableRow key={identity.id}>
                  <TableCell>
                    <Chip label={identity.type} size="small" />
                  </TableCell>
                  <TableCell>{identity.name}</TableCell>
                  <TableCell>{identity.displayName ?? '—'}</TableCell>
                  <TableCell>{new Date(identity.lastSynced).toLocaleString()}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

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
