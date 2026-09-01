import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { AuditFinding, PolicyAuditRun } from './types'

interface Props {
  open: boolean
  onClose: () => void
  run: PolicyAuditRun | null
  /** Implement = disable the referenced rule via the activation toggle (ADR-031). */
  onImplement: (finding: AuditFinding) => void
  /** Modify = the operator takes the suggested YAML into the policy file. */
  onAcknowledge: (finding: AuditFinding) => void
  /** Whether a rule id exists in the current document and is currently enabled. */
  ruleEnabled: (ruleId: string) => boolean
  ruleExists: (ruleId: string) => boolean
}

const SEVERITY_COLOR = { HIGH: 'error', MEDIUM: 'warning', LOW: 'default' } as const

const FRESHNESS = {
  CURRENT: { label: 'current', color: 'warning' as const, hint: 'The referenced rules are unchanged since this audit — the finding still applies.' },
  RULE_CHANGED: { label: 'rule changed', color: 'default' as const, hint: 'A referenced rule was edited after this audit — re-run before acting on it.' },
  ADDRESSED: { label: 'addressed', color: 'success' as const, hint: 'The referenced rules were removed or disabled — nothing left to act on.' },
}

/**
 * The audit results side panel (Stage 31, ADR-031): one card per finding,
 * freshness computed by the gateway against the live document — never a
 * stored status, so "addressed" can only mean the policy actually changed.
 */
export default function AuditDrawer({ open, onClose, run, onImplement, onAcknowledge, ruleEnabled, ruleExists }: Props) {
  const [expandedYaml, setExpandedYaml] = useState<string | null>(null)

  return (
    <Drawer anchor="right" open={open} onClose={onClose}
            slotProps={{ paper: { sx: { width: { xs: '100%', sm: 520 } } } }}>
      <Box sx={{ p: 2.5, display: 'flex', alignItems: 'center', gap: 1, borderBottom: '1px solid #e5e7eb' }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Policy Audit
        </Typography>
        <IconButton onClick={onClose} aria-label="close">✕</IconButton>
      </Box>

      <Box sx={{ p: 2.5, overflowY: 'auto' }}>
        {!run ? (
          <Typography color="text.secondary">
            No audit has been run yet — use “Run Policy Audit”.
          </Typography>
        ) : (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {new Date(run.timestamp).toLocaleString()} · {run.model ?? 'unknown model'} · requested by{' '}
              {run.requestedBy ?? '—'}
            </Typography>

            {run.status === 'PARSE_ERROR' && (
              <Alert severity="warning" sx={{ mb: 2 }}>
                The model’s response could not be parsed into findings — raw report shown below. This is
                reported honestly rather than pretending the audit found nothing.
              </Alert>
            )}

            <Stack spacing={2}>
              {run.findings.map((f) => {
                const fresh = FRESHNESS[f.freshness]
                const implementTarget = f.suggestedAction === 'DISABLE_RULE' ? (f.ruleIds ?? [])[0] : undefined
                const implementable =
                  implementTarget !== undefined && ruleExists(implementTarget) && ruleEnabled(implementTarget)
                return (
                  <Box key={f.id} sx={{ border: '1px solid #e5e7eb', borderRadius: 2, p: 2 }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}>
                      <Chip size="small" label={f.severity} color={SEVERITY_COLOR[f.severity] ?? 'default'} />
                      <Tooltip title={fresh.hint}>
                        <Chip size="small" variant="outlined" label={fresh.label} color={fresh.color} />
                      </Tooltip>
                      <Typography variant="subtitle2">{f.title}</Typography>
                    </Stack>

                    {(f.ruleIds ?? []).length > 0 && (
                      <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', gap: 0.5, mb: 1 }}>
                        {(f.ruleIds ?? []).map((id) => (
                          <Chip
                            key={id}
                            size="small"
                            variant="outlined"
                            label={ruleExists(id) ? id : `${id} (not found)`}
                            sx={{ fontFamily: 'monospace' }}
                          />
                        ))}
                      </Stack>
                    )}

                    <Typography variant="body2" sx={{ mb: 1.5 }}>
                      {f.recommendation}
                    </Typography>

                    {f.acknowledgedBy && (
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                        Taken into work by {f.acknowledgedBy}
                      </Typography>
                    )}

                    <Stack direction="row" spacing={1}>
                      {f.suggestedAction === 'DISABLE_RULE' && (
                        <Tooltip
                          title={
                            implementable
                              ? `Disable rule ${implementTarget} via the activation toggle`
                              : 'Already disabled, or the rule no longer exists'
                          }
                        >
                          <span>
                            <Button size="small" variant="contained" disabled={!implementable}
                                    onClick={() => onImplement(f)}>
                              Implement
                            </Button>
                          </span>
                        </Tooltip>
                      )}
                      {f.suggestedYaml && (
                        <Button size="small" variant="outlined"
                                onClick={() => {
                                  setExpandedYaml(expandedYaml === f.id ? null : f.id)
                                  if (!f.acknowledgedBy) onAcknowledge(f)
                                }}>
                          Modify policy
                        </Button>
                      )}
                    </Stack>

                    {expandedYaml === f.id && f.suggestedYaml && (
                      <Box sx={{ mt: 1.5 }}>
                        <Typography variant="caption" color="text.secondary">
                          Suggested change — apply to zte-policies.yaml and reload (rules are file-defined,
                          ADR-012):
                        </Typography>
                        <Box component="pre"
                             sx={{ p: 1.5, bgcolor: '#f5f6f8', borderRadius: 1, fontSize: '0.78rem',
                                   overflowX: 'auto', whiteSpace: 'pre-wrap' }}>
                          {f.suggestedYaml}
                        </Box>
                        <Button size="small" onClick={() => navigator.clipboard.writeText(f.suggestedYaml ?? '')}>
                          Copy YAML
                        </Button>
                      </Box>
                    )}
                  </Box>
                )
              })}
            </Stack>

            {run.findings.length === 0 && run.rawReport && (
              <Box component="pre" sx={{ mt: 2, p: 1.5, bgcolor: '#f5f6f8', borderRadius: 1,
                                          fontSize: '0.78rem', whiteSpace: 'pre-wrap' }}>
                {run.rawReport}
              </Box>
            )}
          </>
        )}
      </Box>
    </Drawer>
  )
}
