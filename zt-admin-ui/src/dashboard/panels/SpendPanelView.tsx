import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import PanelFrame from './PanelFrame'
import SpendChart from '../SpendChart'
import { usePanel } from './usePanel'
import { agentLabel, compact, euros } from '../format'
import type { SpendPanel } from '../types'

/** CFO panel (Stage 29, ADR-029): where the money went, per day and per agent. */
export default function SpendPanelView({ accessToken }: { accessToken: string }) {
  const { data, loading, forbidden, error } = usePanel<SpendPanel>('/api/v1/dashboard/spend?days=30', accessToken)

  return (
    <PanelFrame title="LLM SPEND · LAST 30 DAYS" loading={loading} forbidden={forbidden} error={error}>
      {data && (
        <Box>
          <SpendChart daily={data.daily} instrumented={data.instrumented} />
          {data.instrumented ? (
            <Box sx={{ mt: 3, overflowX: 'auto' }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Agent</TableCell>
                    <TableCell align="right">Calls</TableCell>
                    <TableCell align="right">Input tokens</TableCell>
                    <TableCell align="right">Output tokens</TableCell>
                    <TableCell align="right">Cost</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.byAgent.map((a) => (
                    <TableRow key={a.agentId}>
                      <TableCell>{agentLabel(a.agentId)}</TableCell>
                      <TableCell align="right">{a.calls}</TableCell>
                      <TableCell align="right">{compact(a.inputTokens)}</TableCell>
                      <TableCell align="right">{compact(a.outputTokens)}</TableCell>
                      <TableCell align="right"><b>{euros(a.costMicros)}</b></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
                Totals: {euros(data.totals.costMicros)} · {compact(data.totals.inputTokens + data.totals.outputTokens)} tokens
                · {data.totals.calls} calls. Prices come from each reporter's own configuration and are stored
                as of the call, so past windows keep the price that applied then.
              </Typography>
            </Box>
          ) : (
            <Typography variant="body2" sx={{ mt: 2 }}>
              Nothing metered yet. This isn’t “€0 spent” — it means no component has reported usage.
              zt-agents reports its Policy Auditor runs automatically; other agents can POST to{' '}
              <code>/api/v1/internal/metering/llm</code>.
            </Typography>
          )}
        </Box>
      )}
    </PanelFrame>
  )
}
