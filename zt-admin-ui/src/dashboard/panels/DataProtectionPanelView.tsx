import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PanelFrame from './PanelFrame'
import { usePanel } from './usePanel'
import { agentLabel } from '../format'
import type { DataProtectionPanel } from '../types'

/**
 * DPO panel (Stage 29, ADR-029): exactly which fields each agent may read,
 * and whether it may write at all — the ACAP scope, surfaced instead of
 * living only in YAML.
 */
export default function DataProtectionPanelView({ accessToken }: { accessToken: string }) {
  const { data, loading, forbidden, error } = usePanel<DataProtectionPanel>('/api/v1/dashboard/data-protection', accessToken)

  return (
    <PanelFrame title="DATA SCOPES PER AGENT" loading={loading} forbidden={forbidden} error={error}>
      {data && (
        <Stack spacing={2}>
          {data.scopes.map((s) => (
            <Box key={s.agentId} sx={{ border: '1px solid #e5e7eb', borderRadius: 2, p: 2 }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
                <Typography variant="subtitle2">{agentLabel(s.agentId)}</Typography>
                {s.territory && <Chip size="small" variant="outlined" label={`territory: ${s.territory}`} />}
                <Chip
                  size="small"
                  color={s.writeAllowed ? 'warning' : 'success'}
                  label={s.writeAllowed ? 'write allowed' : 'read-only'}
                />
              </Stack>
              {s.reads.map((r) => (
                <Box key={r.resource} sx={{ mb: 0.75 }}>
                  <Typography variant="caption" color="text.secondary">{r.resource}</Typography>
                  <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', gap: 0.5 }}>
                    {r.fields.map((f) => (
                      <Chip key={f} size="small" variant="outlined" label={f} sx={{ fontFamily: 'monospace' }} />
                    ))}
                  </Stack>
                </Box>
              ))}
              {s.reads.length === 0 && (
                <Typography variant="body2">No read grants — this agent may read nothing.</Typography>
              )}
            </Box>
          ))}
          {data.scopes.length === 0 && (
            <Typography variant="body2">
              No ACAP profiles loaded, so no agent has a declared data scope.
            </Typography>
          )}
        </Stack>
      )}
    </PanelFrame>
  )
}
