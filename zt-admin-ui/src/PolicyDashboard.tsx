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
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Switch from '@mui/material/Switch'
import Accordion from '@mui/material/Accordion'
import AccordionSummary from '@mui/material/AccordionSummary'
import AccordionDetails from '@mui/material/AccordionDetails'
import AuditDrawer from './AuditDrawer'
import ConfirmDialog from './ConfirmDialog'
import type { AuditFinding, IdpIdentityEntry, PolicyAuditRun, PolicyDocument, PolicyRule, PolicyRuleOverride, ReloadResult } from './types'

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
  // agentMcpToolHolds rows' `effect` field is unused (kept ALLOW by YAML
  // convention — see PolicyDocument's Javadoc) since matching doesn't go
  // through evaluate()'s ALLOW/DENY precedence at all (ADR-019). Showing the
  // literal field there would read as "this rule ALLOWs," which is backwards
  // for a category that exists to hold, not allow — Stage 2's "honest, not
  // just technically-correct" pass caught this. Set to override every row's
  // displayed effect regardless of the JSON field.
  effectOverride?: 'HOLD'
}

const CATEGORIES: Category[] = [
  { key: 'users2service', title: 'User → Service', description: 'Human user (realm role) → gateway REST service', defaultSourceType: 'ROLE' },
  { key: 'service2service', title: 'Service → Service', description: 'Calling service/agent (JWT azp) → gateway REST service', defaultSourceType: 'CLIENT' },
  { key: 'agentMcpToolCalls', title: 'Agent → MCP Tool', description: 'MCP agent (JWT azp) → MCP tool name', defaultSourceType: 'CLIENT' },
  { key: 'agentMcpToolHolds', title: 'Agent → MCP Tool (Hold)', description: 'MCP tool calls routed to a human for approval, even when Agent → MCP Tool above would ALLOW them (Stage 1, ADR-019)', defaultSourceType: 'CLIENT', effectOverride: 'HOLD' },
]

interface RuleTableProps {
  rules: PolicyRule[]
  identitySet?: Set<string>
  defaultSourceType: IdentityTypeName
  effectOverride?: 'HOLD'
  /** ruleId -> enabled; absence means enabled (Stage 31, ADR-031). */
  overrides: Map<string, boolean>
  /** Rule ids referenced by non-addressed findings of the latest audit. */
  flaggedIds: Set<string>
  onToggle: (rule: PolicyRule, enabled: boolean) => void
}

function RuleTable({ rules, identitySet, defaultSourceType, effectOverride, overrides, flaggedIds, onToggle }: RuleTableProps) {
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
            <TableCell>Active</TableCell>
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
            const enabled = overrides.get(rule.id) ?? true
            const flagged = flaggedIds.has(rule.id)
            // Audit flag paints the row orange; an inactive rule dims it —
            // both can apply at once.
            const rowSx = {
              ...(flagged ? { bgcolor: 'rgba(245, 166, 35, 0.16)' } : orphaned ? { bgcolor: 'warning.light' } : {}),
              ...(enabled ? {} : { opacity: 0.55 }),
            }
            return (
              <TableRow key={rule.id} sx={rowSx}>
                <TableCell>
                  <Tooltip title={enabled
                    ? 'Active — this rule takes effect on match'
                    : 'Inactive — matches are logged but the rule has no effect (ADR-031)'}>
                    <Switch size="small" checked={enabled} onChange={(_, next) => onToggle(rule, next)} />
                  </Tooltip>
                </TableCell>
                <TableCell>
                  {rule.id}
                  {flagged && (
                    <Tooltip title="Flagged by the latest policy audit — see Last Audit Results">
                      <span style={{ marginLeft: 6 }}>🟠</span>
                    </Tooltip>
                  )}
                  {!enabled && <Chip label="inactive" size="small" sx={{ ml: 0.75 }} />}
                </TableCell>
                <TableCell>
                  <Chip
                    label={effectOverride ?? rule.effect}
                    color={effectOverride === 'HOLD' ? 'warning' : rule.effect === 'DENY' ? 'error' : 'success'}
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
                <TableCell>{rule.mcpTarget ? `${rule.mcpTarget}:${rule.target}` : rule.target}</TableCell>
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
  // Stage 31 (ADR-031): activation overlay + AI audit surfacing
  const [overrides, setOverrides] = useState<Map<string, boolean>>(new Map())
  const [auditRun, setAuditRun] = useState<PolicyAuditRun | null>(null)
  const [auditOpen, setAuditOpen] = useState(false)
  const [auditRunning, setAuditRunning] = useState(false)
  const [confirmDenyOff, setConfirmDenyOff] = useState<PolicyRule | null>(null)

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

  const fetchOverrides = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/admin/policies/overrides', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) return
      const rows: PolicyRuleOverride[] = await res.json()
      setOverrides(new Map(rows.map((o) => [o.ruleId, o.enabled])))
    } catch {
      // Toggle state is additive — a failed fetch just renders everything enabled.
    }
  }, [accessToken])

  const fetchLatestAudit = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/admin/policy-audit/latest', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok) setAuditRun(await res.json())
    } catch {
      // Absence of a past audit is a normal state, not an error.
    }
  }, [accessToken])

  useEffect(() => {
    fetchPolicies()
    fetchIdentitySet()
    fetchOverrides()
    fetchLatestAudit()
  }, [fetchPolicies, fetchIdentitySet, fetchOverrides, fetchLatestAudit])

  const applyToggle = async (rule: PolicyRule, enabled: boolean) => {
    try {
      const res = await fetch(`/api/v1/admin/policies/${encodeURIComponent(rule.id)}/enabled`, {
        method: 'PUT',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.error ?? `PUT enabled -> ${res.status}`)
      }
      setSnackbar({
        message: `Rule ${rule.id} ${enabled ? 'activated' : 'deactivated'} — matches ${enabled ? 'take effect again' : 'will be logged but have no effect'}`,
        severity: 'success',
      })
      await Promise.all([fetchOverrides(), fetchLatestAudit()])
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    }
  }

  const handleToggle = (rule: PolicyRule, enabled: boolean) => {
    // Switching a DENY safety net OFF opens the perimeter — never one silent
    // click (ADR-031's chosen posture: allowed, but behind an explicit confirm).
    if (!enabled && rule.effect === 'DENY') {
      setConfirmDenyOff(rule)
      return
    }
    applyToggle(rule, enabled)
  }

  const handleRunAudit = async () => {
    setAuditRunning(true)
    setAuditOpen(true)
    try {
      const res = await fetch('/api/v1/admin/policy-audit/run', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `run -> ${res.status}`)
      setAuditRun(body)
      setSnackbar({ message: `Audit complete: ${body.findings.length} finding(s)`, severity: 'success' })
    } catch (e) {
      setSnackbar({ message: e instanceof Error ? e.message : String(e), severity: 'error' })
    } finally {
      setAuditRunning(false)
    }
  }

  const handleImplement = (finding: AuditFinding) => {
    const target = (finding.ruleIds ?? [])[0]
    const rule = allRules().find((r) => r.id === target)
    if (rule) handleToggle(rule, false)
  }

  const handleAcknowledge = async (finding: AuditFinding) => {
    try {
      const res = await fetch(`/api/v1/admin/policy-audit/latest/findings/${finding.id}/acknowledge`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (res.ok) setAuditRun(await res.json())
    } catch {
      // Acknowledgement is bookkeeping; a failure here must not block reading the YAML.
    }
  }

  const allRules = (): PolicyRule[] =>
    CATEGORIES.flatMap((c) => policies?.[c.key] ?? [])

  const flaggedIds = new Set(
    (auditRun?.findings ?? [])
      .filter((f) => f.freshness !== 'ADDRESSED')
      .flatMap((f) => f.ruleIds ?? []),
  )

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
        <Stack direction="row" spacing={1}>
          <Button variant="outlined" onClick={handleRunAudit} disabled={auditRunning}>
            {auditRunning ? 'Auditing… (10–60s)' : 'Run Policy Audit'}
          </Button>
          <Button variant="outlined" onClick={() => setAuditOpen(true)} disabled={auditRunning}>
            Last Audit Results{auditRun ? ` (${auditRun.findings.length})` : ''}
          </Button>
          <Button variant="contained" onClick={handleReload} disabled={reloading}>
            {reloading ? 'Reloading…' : 'Reload Policies'}
          </Button>
        </Stack>
      </Box>

      <Stack spacing={1}>
        {CATEGORIES.map((category) => {
          const rules = policies?.[category.key] ?? []
          return (
            <Accordion key={category.key} defaultExpanded={rules.length > 0}>
              <AccordionSummary sx={{ '& .MuiAccordionSummary-content': { alignItems: 'center', gap: 1 } }}>
                <span>▾</span>
                <Box>
                  <Typography variant="subtitle1">
                    {category.title} ({rules.length})
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {category.description}
                  </Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails sx={{ p: 0 }}>
                <RuleTable
                  rules={rules}
                  identitySet={identitySet}
                  defaultSourceType={category.defaultSourceType}
                  effectOverride={category.effectOverride}
                  overrides={overrides}
                  flaggedIds={flaggedIds}
                  onToggle={handleToggle}
                />
              </AccordionDetails>
            </Accordion>
          )
        })}
      </Stack>

      <AuditDrawer
        open={auditOpen}
        onClose={() => setAuditOpen(false)}
        run={auditRun}
        onImplement={handleImplement}
        onAcknowledge={handleAcknowledge}
        ruleEnabled={(id) => overrides.get(id) ?? true}
        ruleExists={(id) => allRules().some((r) => r.id === id)}
      />

      <ConfirmDialog
        open={confirmDenyOff !== null}
        title="Deactivate a DENY rule?"
        confirmLabel="Deactivate"
        message={
          confirmDenyOff
            ? `"${confirmDenyOff.id}" is a DENY rule — switching it off removes that protection for every matching call until it is re-enabled. Matches will be logged but nothing will be blocked by it.`
            : ''
        }
        onConfirm={() => {
          if (confirmDenyOff) applyToggle(confirmDenyOff, false)
          setConfirmDenyOff(null)
        }}
        onCancel={() => setConfirmDenyOff(null)}
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
