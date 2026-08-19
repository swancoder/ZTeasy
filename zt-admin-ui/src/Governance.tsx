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
import type { AcapProfileView, AgentActivitySummary, GovernanceReport, RequestLogEntry } from './types'

interface Props {
  accessToken: string
}

// today's date compared as a plain yyyy-mm-dd string (reauthDue is stored the same
// way) — avoids a timezone-sensitive Date parse for what's just a calendar-day compare.
function isPastDue(isoDate: string | null): boolean {
  if (!isoDate) return false
  return isoDate < new Date().toISOString().slice(0, 10)
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
          {acapProfiles.map(({ profile, currentThresholdUsage }) => {
            const overdue = isPastDue(profile.agent?.reauthDue ?? null)
            return (
              <Card key={profile.agentId} variant="outlined" sx={{ width: 340 }}>
                <CardContent>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <Box>
                      <Typography variant="subtitle2">{profile.agent?.name ?? profile.agentId}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {profile.agentId}
                      </Typography>
                    </Box>
                    <Chip label={profile.territory} size="small" />
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
                  {profile.agent?.reauthDue && (
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                      <Typography variant="body2">Re-auth due: {profile.agent.reauthDue}</Typography>
                      {overdue && <Chip label="OVERDUE" size="small" color="error" />}
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
                </CardContent>
              </Card>
            )
          })}
        </Stack>
      )}
    </Box>
  )
}
