import { useCallback, useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Snackbar from '@mui/material/Snackbar'
import Alert from '@mui/material/Alert'
import AppBar from '@mui/material/AppBar'
import Toolbar from '@mui/material/Toolbar'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardActions from '@mui/material/CardActions'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'
import ConfirmDialog from './ConfirmDialog'
import type { PendingApproval } from './types'

const POLL_INTERVAL_MS = 15_000

// The standalone Approval Center (ADR-026): the same 🟡 HOLD queue the Admin
// Console's Approvals tab shows (ADR-019), but as its own SPA at /approver/
// with its own Keycloak client — open to any authenticated realm user, not
// just ADMIN. Card layout instead of the admin table: an approver decides one
// call at a time, so each held call gets a card with the full context and two
// buttons, rather than a dense ops table.
export default function App() {
  const auth = useAuth()

  if (auth.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
        <CircularProgress />
      </Box>
    )
  }

  if (auth.error) {
    return (
      <Box sx={{ p: 4 }}>
        <Typography color="error">Authentication error: {auth.error.message}</Typography>
        <Button variant="contained" sx={{ mt: 2 }} onClick={() => auth.signinRedirect()}>
          Retry sign in
        </Button>
      </Box>
    )
  }

  if (!auth.isAuthenticated) {
    return (
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100vh',
          gap: 2,
        }}
      >
        <Typography variant="h4">ZTE Approval Center</Typography>
        <Typography color="text.secondary" sx={{ maxWidth: 420, textAlign: 'center' }}>
          Review and decide AI-agent tool calls held for human approval. Sign in with your
          Keycloak account to continue.
        </Typography>
        <Button variant="contained" size="large" onClick={() => auth.signinRedirect()}>
          Sign in
        </Button>
      </Box>
    )
  }

  return <ApprovalQueue accessToken={auth.user?.access_token ?? ''} username={auth.user?.profile.preferred_username} onSignOut={() => auth.removeUser()} />
}

interface QueueProps {
  accessToken: string
  username: string | undefined
  onSignOut: () => void
}

function ApprovalQueue({ accessToken, username, onSignOut }: QueueProps) {
  const [approvals, setApprovals] = useState<PendingApproval[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [snackbar, setSnackbar] = useState<{ message: string; severity: 'success' | 'error' } | null>(null)
  const [decidingId, setDecidingId] = useState<string | null>(null)
  const [declineTarget, setDeclineTarget] = useState<PendingApproval | null>(null)

  const fetchApprovals = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/approver/approvals', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/approver/approvals -> ${res.status}`)
      }
      setApprovals(await res.json())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [accessToken])

  useEffect(() => {
    fetchApprovals().finally(() => setLoading(false))
    // A held call can arrive at any moment — poll so an open Approval Center
    // page picks it up without a manual refresh.
    const timer = setInterval(fetchApprovals, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [fetchApprovals])

  const decide = async (approval: PendingApproval, action: 'approve' | 'reject') => {
    setDecidingId(approval.id)
    try {
      const res = await fetch(`/api/v1/approver/approvals/${approval.id}/${action}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok) {
        setSnackbar({
          message: `${approval.toolName} (${approval.displayIdentity ?? approval.agentId}) ${action === 'approve' ? 'allowed' : 'denied'}`,
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

  return (
    <Box sx={{ bgcolor: 'background.default', minHeight: '100vh' }}>
      <AppBar position="static">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            ZTE Approval Center
          </Typography>
          <Typography variant="body2">{username}</Typography>
          <Button color="inherit" onClick={onSignOut}>
            Sign out
          </Button>
        </Toolbar>
      </AppBar>

      <Box sx={{ p: 3, maxWidth: 720, mx: 'auto' }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h5">Pending approvals ({approvals.length})</Typography>
          <Button variant="outlined" size="small" onClick={fetchApprovals}>
            Refresh
          </Button>
        </Box>

        {loading ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
            <CircularProgress />
          </Box>
        ) : error ? (
          <Alert severity="error">{error}</Alert>
        ) : approvals.length === 0 ? (
          <Typography color="text.secondary">
            Nothing to review right now — agent tool calls held by policy will show up here.
          </Typography>
        ) : (
          <Stack spacing={2}>
            {approvals.map((approval) => (
              <Card
                key={approval.id}
                sx={{
                  borderColor: 'warning.main',
                  bgcolor: 'rgba(245, 166, 35, 0.04)',
                }}
              >
                <CardContent>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                    <Chip label="HELD" color="warning" size="small" />
                    <Typography variant="subtitle1" sx={{ fontFamily: 'monospace' }}>
                      {approval.toolName}
                    </Typography>
                  </Stack>
                  <Typography variant="body2" color="text.secondary">
                    Agent: <b>{approval.displayIdentity ?? approval.agentId}</b>
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Requested: {new Date(approval.requestedAt).toLocaleString()}
                  </Typography>
                  {approval.reason && (
                    <Typography variant="body2" color="text.secondary">
                      Held because: {approval.reason}
                    </Typography>
                  )}
                  {approval.argumentsJson && (
                    <Box
                      component="pre"
                      sx={{
                        mt: 1,
                        p: 1,
                        bgcolor: 'action.hover',
                        borderRadius: 1,
                        fontSize: '0.8rem',
                        overflowX: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {formatArguments(approval.argumentsJson)}
                    </Box>
                  )}
                </CardContent>
                <CardActions sx={{ justifyContent: 'flex-end', px: 2, pb: 2 }}>
                  <Button
                    variant="outlined"
                    color="error"
                    disabled={decidingId === approval.id}
                    onClick={() => setDeclineTarget(approval)}
                  >
                    Deny
                  </Button>
                  <Button
                    variant="contained"
                    color="success"
                    disabled={decidingId === approval.id}
                    onClick={() => decide(approval, 'approve')}
                  >
                    Allow
                  </Button>
                </CardActions>
              </Card>
            ))}
          </Stack>
        )}
      </Box>

      <ConfirmDialog
        open={declineTarget !== null}
        title="Deny this call?"
        confirmLabel="Deny"
        message={
          declineTarget
            ? `Deny "${declineTarget.toolName}" from ${declineTarget.displayIdentity ?? declineTarget.agentId}? The agent (if still connected) receives an honest denial.`
            : ''
        }
        onConfirm={() => {
          if (declineTarget) decide(declineTarget, 'reject')
          setDeclineTarget(null)
        }}
        onCancel={() => setDeclineTarget(null)}
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

function formatArguments(argumentsJson: string): string {
  try {
    return JSON.stringify(JSON.parse(argumentsJson), null, 2)
  } catch {
    return argumentsJson
  }
}
