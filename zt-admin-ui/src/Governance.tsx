import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
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
import TextField from '@mui/material/TextField'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import AcapLifecycleDialog from './AcapLifecycleDialog'
import ConfirmDialog from './ConfirmDialog'
import type {
  AcapProfileView,
  AcapReauthorization,
  AgentActivitySummary,
  GovernanceReport,
  RequestLogEntry,
} from './types'

interface Props {
  accessToken: string
}

const WINDOW_OPTIONS = [
  { hours: 1, label: 'Last hour' },
  { hours: 24, label: 'Last 24 hours' },
  { hours: 168, label: 'Last 7 days' },
]

// The historical/reporting half of governance (Stage 4, ADR-021) — the
// Approvals tab already covers the pending-queue half. Per-agent
// ALLOW/DENY/HOLD activity plus a live out-of-policy-attempts feed, both
// read from the same request_logs audit trail every prior stage already
// writes to.
export default function Governance({ accessToken }: Props) {
  const [hours, setHours] = useState(24)
  const [activity, setActivity] = useState<AgentActivitySummary[]>([])
  const [outOfPolicy, setOutOfPolicy] = useState<RequestLogEntry[]>([])
  const [acapProfiles, setAcapProfiles] = useState<AcapProfileView[]>([])
  // Stage 32 (ADR-032): lifecycle actions on the cards
  const [suspendTarget, setSuspendTarget] = useState<AcapProfileView | null>(null)
  const [reauthTarget, setReauthTarget] = useState<AcapProfileView | null>(null)
  const [history, setHistory] = useState<Record<string, AcapReauthorization[]>>({})
  const [busyAgent, setBusyAgent] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const authHeaders = useCallback(() => ({ Authorization: `Bearer ${accessToken}` }), [accessToken])

  const fetchData = useCallback(async () => {
    setError(null)
    try {
      const [activityRes, outOfPolicyRes, acapProfilesRes] = await Promise.all([
        fetch(`/api/v1/admin/governance/agent-activity?hours=${hours}`, { headers: authHeaders() }),
        fetch('/api/v1/admin/governance/out-of-policy', { headers: authHeaders() }),
        fetch('/api/v1/admin/acap-profiles', { headers: authHeaders() }),
      ])
      if (!activityRes.ok) throw new Error(`GET .../agent-activity -> ${activityRes.status}`)
      if (!outOfPolicyRes.ok) throw new Error(`GET .../out-of-policy -> ${outOfPolicyRes.status}`)
      if (!acapProfilesRes.ok) throw new Error(`GET .../acap-profiles -> ${acapProfilesRes.status}`)
      setActivity(await activityRes.json())
      setOutOfPolicy(await outOfPolicyRes.json())
      setAcapProfiles(await acapProfilesRes.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [authHeaders, hours])

  useEffect(() => {
    setLoading(true)
    fetchData().finally(() => setLoading(false))
  }, [fetchData])

  // ── Stage 32 (ADR-032): lifecycle actions ──

  const setStatus = async (agentId: string, status: 'ACTIVE' | 'SUSPENDED' | 'RETIRED') => {
    setBusyAgent(agentId)
    try {
      const res = await fetch(`/api/v1/admin/acap-profiles/${encodeURIComponent(agentId)}/status`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      })
      if (!res.ok) throw new Error(`status -> ${res.status}`)
      await fetchData()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyAgent(null)
    }
  }

  const reauthorize = async (agentId: string, nextDue: string, note: string) => {
    setBusyAgent(agentId)
    try {
      const res = await fetch(`/api/v1/admin/acap-profiles/${encodeURIComponent(agentId)}/reauthorize`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ nextDue, note }),
      })
      if (!res.ok) throw new Error(`reauthorize -> ${res.status}`)
      await Promise.all([fetchData(), loadHistory(agentId)])
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyAgent(null)
    }
  }

  const loadHistory = async (agentId: string) => {
    try {
      const res = await fetch(`/api/v1/admin/acap-profiles/${encodeURIComponent(agentId)}/reauthorizations`, {
        headers: authHeaders(),
      })
      if (res.ok) {
        const rows: AcapReauthorization[] = await res.json()
        setHistory((prev) => ({ ...prev, [agentId]: rows }))
      }
    } catch {
      // History is supplementary — its absence must not break the card.
    }
  }

  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      await fetchData()
    } finally {
      setRefreshing(false)
    }
  }

  const handleExport = async () => {
    setExporting(true)
    try {
      const res = await fetch(`/api/v1/admin/governance/report?hours=${hours}`, { headers: authHeaders() })
      if (!res.ok) throw new Error(`GET .../report -> ${res.status}`)
      const report: GovernanceReport = await res.json()
      const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `zte-governance-report-${hours}h.json`
      link.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setExporting(false)
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
        <Typography variant="h5">Governance</Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <TextField
            select
            size="small"
            label="Window"
            value={hours}
            onChange={(e) => setHours(Number(e.target.value))}
            sx={{ minWidth: 160 }}
          >
            {WINDOW_OPTIONS.map((opt) => (
              <MenuItem key={opt.hours} value={opt.hours}>
                {opt.label}
              </MenuItem>
            ))}
          </TextField>
          <Button variant="outlined" onClick={handleRefresh} disabled={refreshing}>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
          <Button variant="contained" onClick={handleExport} disabled={exporting}>
            {exporting ? 'Exporting…' : 'Export Report'}
          </Button>
        </Stack>
      </Box>

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Agent Activity ({activity.length})
      </Typography>
      {activity.length === 0 ? (
        <Typography color="text.secondary" sx={{ mb: 3 }}>
          No MCP agent activity in this window.
        </Typography>
      ) : (
        <TableContainer sx={{ mb: 4 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Agent</TableCell>
                <TableCell>Allow</TableCell>
                <TableCell>Hold</TableCell>
                <TableCell>Deny</TableCell>
                <TableCell>Last Activity</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {activity.map((summary) => (
                <TableRow key={summary.agentId}>
                  <TableCell>{summary.agentId}</TableCell>
                  <TableCell>
                    <Chip label={summary.allowCount} size="small" color="success" />
                  </TableCell>
                  <TableCell>
                    <Chip label={summary.holdCount} size="small" color={summary.holdCount > 0 ? 'warning' : 'default'} />
                  </TableCell>
                  <TableCell>
                    <Chip label={summary.denyCount} size="small" color={summary.denyCount > 0 ? 'error' : 'default'} />
                  </TableCell>
                  <TableCell>{summary.lastActivity ? new Date(summary.lastActivity).toLocaleString() : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Out-of-Policy Attempts ({outOfPolicy.length})
      </Typography>
      {outOfPolicy.length === 0 ? (
        <Typography color="text.secondary">No denied agent calls recorded yet.</Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Timestamp</TableCell>
                <TableCell>Agent</TableCell>
                <TableCell>Tool</TableCell>
                <TableCell>Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {outOfPolicy.map((entry) => (
                <TableRow key={entry.id}>
                  <TableCell>{new Date(entry.timestamp).toLocaleString()}</TableCell>
                  <TableCell>{entry.agentId ?? '—'}</TableCell>
                  <TableCell>{entry.toolName ?? '—'}</TableCell>
                  <TableCell sx={{ maxWidth: 400 }}>
                    <Tooltip title={entry.message ?? ''} arrow>
                      <span style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {entry.message ?? '—'}
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Typography variant="subtitle1" sx={{ mt: 4, mb: 1 }}>
        ACAP Profiles ({acapProfiles.length})
      </Typography>
      {acapProfiles.length === 0 ? (
        <Typography color="text.secondary">
          No ACAP profiles loaded — agents without one are governed by the coarse policy rules alone.
        </Typography>
      ) : (
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
          {acapProfiles.map((view) => {
            const { profile, currentThresholdUsage } = view
            // Stage 32 (ADR-032): overdue and status come from the gateway's
            // lifecycle overlay, not from the file date this tab used to read
            // — the operator-managed date wins when one exists.
            const overdue = view.reauthOverdue
            const suspended = view.lifecycleStatus !== 'ACTIVE'
            const busy = busyAgent === profile.agentId
            return (
              <Card
                key={profile.agentId}
                variant="outlined"
                sx={{ width: 340, ...(suspended ? { opacity: 0.7, borderColor: 'error.main' } : {}) }}
              >
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <Box>
                      <Typography variant="subtitle2">{profile.agent?.name ?? profile.agentId}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {profile.agentId}
                      </Typography>
                    </Box>
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                      <Chip label={profile.territory} size="small" />
                      {view.lifecycleStatus !== 'ACTIVE' && (
                        <Chip label={view.lifecycleStatus} size="small" color="error" />
                      )}
                    </Stack>
                  </Stack>

                  {profile.agent?.client && (
                    <Typography variant="body2" sx={{ mt: 1 }}>
                      Client: {profile.agent.client}
                    </Typography>
                  )}
                  {profile.agent?.owner && (
                    <Typography variant="body2">
                      Owner: {profile.agent.owner.name} ({profile.agent.owner.email})
                    </Typography>
                  )}
                  {profile.agent?.deploymentDate && (
                    <Typography variant="body2">Deployed: {profile.agent.deploymentDate}</Typography>
                  )}
                  {view.effectiveReauthDue && (
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                      <Typography variant="body2">Re-auth due: {view.effectiveReauthDue}</Typography>
                      {overdue && (
                        <Tooltip title="Every allowed call is held for a human decision until this agent is re-authorized">
                          <Chip label="OVERDUE" size="small" color="error" />
                        </Tooltip>
                      )}
                    </Stack>
                  )}
                  {profile.risk?.euAiActClass && (
                    <Typography variant="body2" sx={{ mt: 0.5 }}>
                      EU AI Act: {profile.risk.euAiActClass}
                      {profile.risk.internalTier != null && ` · internal tier ${profile.risk.internalTier}`}
                    </Typography>
                  )}

                  <Typography variant="body2" sx={{ mt: 1 }}>
                    Write access: {profile.scope?.writeAllowed ? 'allowed' : 'read-only'}
                  </Typography>
                  {(profile.scope?.read.length ?? 0) > 0 && (
                    <Typography variant="body2" color="text.secondary">
                      Reads: {profile.scope!.read.map((g) => g.resource).join(', ')}
                    </Typography>
                  )}

                  {profile.thresholds.length > 0 && (
                    <Box sx={{ mt: 1 }}>
                      <Typography variant="caption" color="text.secondary">
                        Thresholds
                      </Typography>
                      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mt: 0.5 }}>
                        {profile.thresholds.map((t) => {
                          const used = currentThresholdUsage[t.metric] ?? 0
                          return (
                            <Chip
                              key={t.metric}
                              label={`${t.metric}: ${used}/${t.limit}`}
                              size="small"
                              color={used >= t.limit ? 'warning' : 'default'}
                            />
                          )
                        })}
                      </Stack>
                    </Box>
                  )}

                  <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                    <Button size="small" variant="outlined" disabled={busy}
                            onClick={() => { setReauthTarget(view); loadHistory(profile.agentId) }}>
                      Re-authorize
                    </Button>
                    {suspended ? (
                      <Button size="small" variant="contained" color="success" disabled={busy}
                              onClick={() => setStatus(profile.agentId, 'ACTIVE')}>
                        Resume
                      </Button>
                    ) : (
                      <Button size="small" variant="outlined" color="error" disabled={busy}
                              onClick={() => setSuspendTarget(view)}>
                        Suspend
                      </Button>
                    )}
                  </Stack>

                  {(history[profile.agentId] ?? []).length > 0 && (
                    <Box sx={{ mt: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">
                        Re-authorization history
                      </Typography>
                      {(history[profile.agentId] ?? []).slice(0, 3).map((h) => (
                        <Typography key={h.id} variant="caption" sx={{ display: 'block' }}>
                          {new Date(h.reauthorizedAt).toLocaleDateString()} · {h.reauthorizedBy} → {h.nextDue}
                          {h.note ? ` · ${h.note}` : ''}
                        </Typography>
                      ))}
                    </Box>
                  )}
                </CardContent>
              </Card>
            )
          })}
        </Stack>
      )}

      <ConfirmDialog
        open={suspendTarget !== null}
        title="Suspend this agent?"
        confirmLabel="Suspend"
        message={
          suspendTarget
            ? `Every call from "${suspendTarget.profile.agent?.name ?? suspendTarget.profile.agentId}" will be refused until it is resumed. Its policy grants stay untouched — this is a lifecycle decision, and it is recorded against your name.`
            : ''
        }
        onConfirm={() => {
          if (suspendTarget) setStatus(suspendTarget.profile.agentId, 'SUSPENDED')
          setSuspendTarget(null)
        }}
        onCancel={() => setSuspendTarget(null)}
      />

      <AcapLifecycleDialog
        open={reauthTarget !== null}
        agentName={reauthTarget?.profile.agent?.name ?? reauthTarget?.profile.agentId ?? ''}
        defaultNextDue={new Date(Date.now() + 365 * 24 * 3600 * 1000).toISOString().slice(0, 10)}
        onConfirm={(nextDue, note) => {
          if (reauthTarget) reauthorize(reauthTarget.profile.agentId, nextDue, note)
          setReauthTarget(null)
        }}
        onCancel={() => setReauthTarget(null)}
      />
    </Box>
  )
}
