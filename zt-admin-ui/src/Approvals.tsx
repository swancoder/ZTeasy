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
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import ConfirmDialog from './ConfirmDialog'
import type { PendingApproval } from './types'

interface Props {
  accessToken: string
}

// The 🟡 outcome (Stage 1, ADR-019): tool calls held by an agentMcpToolHolds
// rule, awaiting a human's Approve/Reject here before (or instead of) reaching
// the backend MCP server. Mirrors Inventory.tsx's fetch/snackbar/confirm shape.
/** Same wording the Approval Center uses, from the gateway's own countdown (ADR-034). */
function formatRemaining(seconds: number): string {
  if (seconds <= 0) return 'expiring now'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m`
  return `${seconds}s`
}

export default function Approvals({ accessToken }: Props) {
  const [approvals, setApprovals] = useState<PendingApproval[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [snackbar, setSnackbar] = useState<{ message: string; severity: 'success' | 'error' } | null>(null)
  const [decidingId, setDecidingId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<PendingApproval | null>(null)

  const fetchApprovals = useCallback(async () => {
    setError(null)
    try {
      const res = await fetch('/api/v1/admin/approvals', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/admin/approvals -> ${res.status}`)
      }
      setApprovals(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [accessToken])

  useEffect(() => {
    fetchApprovals().finally(() => setLoading(false))
  }, [fetchApprovals])

  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      await fetchApprovals()
    } finally {
      setRefreshing(false)
    }
  }

  const decide = async (approval: PendingApproval, action: 'approve' | 'reject') => {
    setDecidingId(approval.id)
    try {
      const res = await fetch(`/api/v1/admin/approvals/${approval.id}/${action}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok) {
        setSnackbar({
          message: `${approval.toolName} (${approval.agentId}) ${action === 'approve' ? 'approved' : 'rejected'}`,
          severity: 'success',
        })
        await fetchApprovals()
      } else {
        const body = await res.json().catch(() => ({}))
        setSnackbar({ message: `${action} failed: ${body.error ?? res.status}`, severity: 'error' })
      }
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setDecidingId(null)
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
        <Typography variant="h5">Pending Approvals ({approvals.length})</Typography>
        <Button variant="outlined" onClick={handleRefresh} disabled={refreshing}>
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </Button>
      </Box>

      {approvals.length === 0 ? (
        <Typography color="text.secondary">
          Nothing held right now — calls matching an agentMcpToolHolds rule will show up here.
        </Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Requested</TableCell>
                <TableCell>Expires</TableCell>
                <TableCell>Agent</TableCell>
                <TableCell>Tool</TableCell>
                <TableCell>Routed to</TableCell>
                <TableCell>Arguments</TableCell>
                <TableCell>Reason</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {approvals.map((approval) => (
                <TableRow key={approval.id}>
                  <TableCell>{new Date(approval.requestedAt).toLocaleString()}</TableCell>
                  <TableCell sx={{ color: approval.secondsRemaining < 3600 ? 'error.main' : 'text.secondary' }}>
                    {approval.status === 'EXPIRED' ? 'expired' : formatRemaining(approval.secondsRemaining)}
                  </TableCell>
                  <TableCell>{approval.displayIdentity ?? approval.agentId}</TableCell>
                  <TableCell>{approval.toolName}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                    {approval.routeTo ?? 'anyone'}
                  </TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem', maxWidth: 260 }}>
                    <Tooltip title={approval.argumentsJson ?? '—'}>
                      <span style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {approval.argumentsJson ?? '—'}
                      </span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{approval.reason ?? '—'}</TableCell>
                  <TableCell align="right">
                    <Tooltip title={approval.canDecide ? '' : approval.refusalReason ?? ''}>
                      <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                        <Button
                          size="small"
                          variant="contained"
                          color="success"
                          disabled={decidingId === approval.id || !approval.canDecide}
                          onClick={() => decide(approval, 'approve')}
                        >
                          Approve
                        </Button>
                        <Button
                          size="small"
                          variant="outlined"
                          color="error"
                          disabled={decidingId === approval.id || !approval.canDecide}
                          onClick={() => setRejectTarget(approval)}
                        >
                          Reject
                        </Button>
                      </Stack>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <ConfirmDialog
        open={rejectTarget !== null}
        title="Reject this call?"
        confirmLabel="Reject"
        message={
          rejectTarget
            ? `Reject "${rejectTarget.toolName}" from ${rejectTarget.displayIdentity ?? rejectTarget.agentId}? The agent (if still connected) receives an honest denial.`
            : ''
        }
        onConfirm={() => {
          if (rejectTarget) decide(rejectTarget, 'reject')
          setRejectTarget(null)
        }}
        onCancel={() => setRejectTarget(null)}
      />

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
