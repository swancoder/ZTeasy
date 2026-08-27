import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import PanelFrame from './PanelFrame'
import { usePanel } from './usePanel'
import { agentLabel } from '../format'
import type { OperationsPanel } from '../types'

const STATUS_COLOR: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
  ACTIVE: 'success',
  WARNING: 'warning',
  DOWN: 'error',
  PENDING: 'default',
}

/** CTO panel (Stage 29, ADR-029): who is calling what, and is the fleet healthy. */
export default function OperationsPanelView({ accessToken }: { accessToken: string }) {
  const { data, loading, forbidden, error } = usePanel<OperationsPanel>('/api/v1/dashboard/operations', accessToken)

  return (
    <PanelFrame title="AGENT ACTIVITY & REGISTRY HEALTH" loading={loading} forbidden={forbidden} error={error}>
      {data && (
        <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: '1.3fr 1fr' } }}>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Agent</TableCell>
                  <TableCell align="right">Allow</TableCell>
                  <TableCell align="right">Hold</TableCell>
                  <TableCell align="right">Deny</TableCell>
                  <TableCell>Last activity</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.agents.map((a) => (
                  <TableRow key={a.agentId}>
                    <TableCell>{agentLabel(a.agentId)}</TableCell>
                    <TableCell align="right" sx={{ color: 'success.main', fontWeight: 600 }}>{a.allowCount}</TableCell>
                    <TableCell align="right" sx={{ color: 'warning.main', fontWeight: 600 }}>{a.holdCount}</TableCell>
                    <TableCell align="right" sx={{ color: 'error.main', fontWeight: 600 }}>{a.denyCount}</TableCell>
                    <TableCell>{a.lastActivity ? new Date(a.lastActivity).toLocaleString() : '—'}</TableCell>
                  </TableRow>
                ))}
                {data.agents.length === 0 && (
                  <TableRow><TableCell colSpan={5}>
                    <Typography variant="body2">No agent traffic in this window.</Typography>
                  </TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </Box>
          <Box>
            <Typography variant="caption" color="text.secondary">REGISTRY</Typography>
            <Table size="small">
              <TableBody>
                {data.registry.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{r.name}</TableCell>
                    <TableCell><Typography variant="caption" color="text.secondary">{r.targetType}</Typography></TableCell>
                    <TableCell align="right">
                      <Chip size="small" label={r.status} color={STATUS_COLOR[r.status] ?? 'default'}
                            variant={r.status === 'ACTIVE' ? 'filled' : 'outlined'} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        </Box>
      )}
    </PanelFrame>
  )
}
