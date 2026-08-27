import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import PanelFrame from './PanelFrame'
import { usePanel } from './usePanel'
import { agentLabel } from '../format'
import type { RiskPanel } from '../types'

/** Board / Risk panel (Stage 29, ADR-029): risk tiers, overdue re-auth, refusals. */
export default function RiskPanelView({ accessToken }: { accessToken: string }) {
  const { data, loading, forbidden, error } = usePanel<RiskPanel>('/api/v1/dashboard/risk', accessToken)

  return (
    <PanelFrame title="RISK POSTURE & OUT-OF-POLICY ATTEMPTS" loading={loading} forbidden={forbidden} error={error}>
      {data && (
        <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Box>
            <Typography variant="caption" color="text.secondary">ACAP PROFILES</Typography>
            <Stack spacing={1.5} sx={{ mt: 1 }}>
              {data.profiles.map((p) => (
                <Box key={p.agentId} sx={{ border: '1px solid #e5e7eb', borderRadius: 2, p: 1.5 }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                    <Typography variant="subtitle2">{p.displayName}</Typography>
                    {p.euAiActClass && <Chip size="small" variant="outlined" label={`EU AI Act: ${p.euAiActClass}`} />}
                    {p.internalTier != null && <Chip size="small" variant="outlined" label={`Tier ${p.internalTier}`} />}
                    {p.overdue && <Chip size="small" color="error" label="OVERDUE" />}
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {agentLabel(p.agentId)} · re-authorization due {p.reauthDue ?? 'not set'}
                  </Typography>
                </Box>
              ))}
              {data.profiles.length === 0 && <Typography variant="body2">No ACAP profiles loaded.</Typography>}
            </Stack>
          </Box>
          <Box sx={{ overflowX: 'auto' }}>
            <Typography variant="caption" color="text.secondary">LATEST REFUSALS</Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>When</TableCell>
                  <TableCell>Agent</TableCell>
                  <TableCell>Tool</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.outOfPolicy.slice(0, 12).map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{new Date(r.timestamp).toLocaleString()}</TableCell>
                    <TableCell>{r.agentId ? agentLabel(r.agentId) : '—'}</TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.78rem' }}>{r.toolName ?? '—'}</TableCell>
                  </TableRow>
                ))}
                {data.outOfPolicy.length === 0 && (
                  <TableRow><TableCell colSpan={3}>
                    <Typography variant="body2">Nothing refused in this window.</Typography>
                  </TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </Box>
        </Box>
      )}
    </PanelFrame>
  )
}
