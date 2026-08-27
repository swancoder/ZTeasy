import { useCallback, useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import KpiCard from './KpiCard'
import DecisionBar from './DecisionBar'
import SpendChart from './SpendChart'
import SpendPanelView from './panels/SpendPanelView'
import OperationsPanelView from './panels/OperationsPanelView'
import RiskPanelView from './panels/RiskPanelView'
import DataProtectionPanelView from './panels/DataProtectionPanelView'
import ApprovalQueueCard from './panels/ApprovalQueueCard'
import { euros, compact } from './format'
import type { Audience, SummaryPanel, SpendPanel } from './types'

interface Props {
  accessToken: string
  /** Realm roles from the token — which audiences this user may actually see. */
  roles: string[]
}

const AUDIENCES: { id: Audience; label: string; requires: string[] }[] = [
  { id: 'CEO', label: 'Overview · CEO', requires: ['CEO', 'ADMIN'] },
  { id: 'CFO', label: 'CFO', requires: ['CFO', 'ADMIN', 'CEO', 'BOARD'] },
  { id: 'CTO', label: 'CTO', requires: ['CTO', 'ADMIN', 'CEO', 'BOARD'] },
  { id: 'BOARD', label: 'Board · Risk', requires: ['BOARD', 'ADMIN', 'CEO'] },
  { id: 'DPO', label: 'DPO', requires: ['DPO', 'ADMIN', 'CEO', 'BOARD'] },
]

/**
 * The executive dashboard (Stage 29, ADR-029).
 *
 * The audience tabs are a reflection of the user's realm roles, not a
 * substitute for authorization: the gateway enforces the `u2s-dashboard-*`
 * rules on every fetch, so hiding a tab is a courtesy and a `403` is the
 * actual control. A CFO signing in sees the CFO tab because the gate would
 * refuse them the others anyway.
 */
export default function Dashboard({ accessToken, roles }: Props) {
  const visible = useMemo(
    () => AUDIENCES.filter((a) => a.requires.some((r) => roles.includes(r))),
    [roles],
  )
  const [audience, setAudience] = useState<Audience>(visible[0]?.id ?? 'CFO')
  const [summary, setSummary] = useState<SummaryPanel | null>(null)
  const [spend, setSpend] = useState<SpendPanel | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const authFetch = useCallback(
    async <T,>(path: string): Promise<T | null> => {
      const res = await fetch(path, { headers: { Authorization: `Bearer ${accessToken}` } })
      // 403 is a legitimate answer here, not a failure: it means this role
      // isn't granted that panel. Render what we can rather than blanking out.
      if (res.status === 403) return null
      if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`)
      return (await res.json()) as T
    },
    [accessToken],
  )

  useEffect(() => {
    let cancelled = false
    setError(null)
    Promise.all([
      authFetch<SummaryPanel>('/api/v1/dashboard/summary'),
      authFetch<SpendPanel>('/api/v1/dashboard/spend?days=30'),
    ])
      .then(([s, sp]) => {
        if (cancelled) return
        setSummary(s)
        setSpend(sp)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [authFetch])

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box sx={{ p: 3, maxWidth: 1400, mx: 'auto' }}>
      <Typography variant="body2" sx={{ mb: 1.5 }}>
        Nordwind Components · window: last 30 days · data from the ZTeasy engine
      </Typography>

      <ToggleButtonGroup
        exclusive
        size="small"
        value={audience}
        onChange={(_, next: Audience | null) => next && setAudience(next)}
        sx={{ mb: 3, flexWrap: 'wrap', gap: 1, '& .MuiToggleButton-root': { borderRadius: '999px !important', border: '1px solid #e5e7eb', px: 2 } }}
      >
        {visible.map((a) => (
          <ToggleButton key={a.id} value={a.id}>
            {a.label}
          </ToggleButton>
        ))}
      </ToggleButtonGroup>

      {error && <Alert severity="error" sx={{ mb: 3 }}>{error}</Alert>}

      {summary && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', mb: 3 }}>
          <KpiCard
            label="Agents governed"
            value={`${summary.agentsGoverned} / ${summary.agentsSeen}`}
            hint="have an ACAP profile, of agents seen by the gate"
          />
          <KpiCard
            label="Actions in window"
            value={compact(summary.actionsInWindow)}
            hint="tool calls the gate decided"
          />
          <KpiCard
            label="LLM spend · token metering"
            value={summary.llmCalls > 0 ? euros(summary.spendMicros) : '—'}
            hint={summary.llmCalls > 0
              ? `${compact(summary.tokensTotal)} tokens · ${summary.llmCalls} calls`
              : 'not reported yet'}
            muted={summary.llmCalls === 0}
          />
          <KpiCard
            label="Awaiting approval"
            value={String(summary.awaitingApproval)}
            hint="need a human decision"
            tone={summary.awaitingApproval > 0 ? 'warning' : 'default'}
          />
          <KpiCard
            label="ACAP profiles current"
            value={`${summary.acapProfilesCurrent} / ${summary.acapProfilesTotal}`}
            hint={summary.acapProfilesOverdue > 0
              ? `${summary.acapProfilesOverdue} overdue — re-authorization needed`
              : 'all re-authorizations current'}
            tone={summary.acapProfilesOverdue > 0 ? 'error' : 'default'}
          />
        </Box>
      )}

      {(audience === 'CEO' || audience === 'BOARD') && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1.1fr 1fr' }, mb: 3 }}>
          <Card>
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
                <Typography variant="subtitle2" color="text.secondary">DAILY SPEND</Typography>
                <Chip size="small" label="CFO" variant="outlined" />
              </Stack>
              <SpendChart daily={spend?.daily ?? []} instrumented={spend?.instrumented ?? false} />
            </CardContent>
          </Card>
          <Card>
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
                <Typography variant="subtitle2" color="text.secondary">GATE DECISIONS</Typography>
                <Chip size="small" label="BOARD" variant="outlined" />
              </Stack>
              {summary && <DecisionBar decisions={summary.decisions} />}
              <Box sx={{ mt: 3 }}>
                <ApprovalQueueCard accessToken={accessToken} />
              </Box>
            </CardContent>
          </Card>
        </Box>
      )}

      {audience === 'CFO' && <SpendPanelView accessToken={accessToken} />}
      {audience === 'CTO' && <OperationsPanelView accessToken={accessToken} />}
      {audience === 'BOARD' && <RiskPanelView accessToken={accessToken} />}
      {audience === 'DPO' && <DataProtectionPanelView accessToken={accessToken} />}
    </Box>
  )
}
