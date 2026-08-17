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
import Tooltip from '@mui/material/Tooltip'
import type { RequestLogEntry } from './types'

interface Props {
  accessToken: string
}

export default function AuditTrail({ accessToken }: Props) {
  const [logs, setLogs] = useState<RequestLogEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchLogs = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/v1/admin/audit-logs', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!res.ok) {
        throw new Error(`GET /api/v1/admin/audit-logs -> ${res.status}`)
      }
      setLogs(await res.json())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    fetchLogs()
  }, [fetchLogs])

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
        <Typography variant="h5">Request Audit Trail (latest {logs.length})</Typography>
        <Button variant="outlined" onClick={fetchLogs}>
          Refresh
        </Button>
      </Box>

      {logs.length === 0 ? (
        <Typography color="text.secondary">No audit log entries yet.</Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Timestamp</TableCell>
                <TableCell>Trace ID</TableCell>
                <TableCell>Client IP</TableCell>
                <TableCell>Agent ID</TableCell>
                <TableCell>Initiator / OBO User</TableCell>
                <TableCell>Method</TableCell>
                <TableCell>Path</TableCell>
                <TableCell>Target</TableCell>
                <TableCell>Tool</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Decision</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {logs.map((entry) => (
                <TableRow key={entry.id}>
                  <TableCell>{new Date(entry.timestamp).toLocaleString()}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{entry.traceId}</TableCell>
                  <TableCell>{entry.clientIp ?? '—'}</TableCell>
                  <TableCell>{entry.agentId ?? '—'}</TableCell>
                  <TableCell sx={{ fontSize: '0.75rem' }}>
                    {entry.initiatorClient ?? '—'} / {entry.originalUserObo ?? '—'}
                  </TableCell>
                  <TableCell>{entry.httpMethod ?? '—'}</TableCell>
                  <TableCell>{entry.path}</TableCell>
                  <TableCell>{entry.targetService ?? '—'}</TableCell>
                  <TableCell>
                    {entry.toolName ? (
                      // message holds "Session: <id>[. <deny reason>][. Args: {...}]" (folded in
                      // rather than a dedicated column — see LoggingMcpAuditService) — the actual
                      // JSON-RPC params.arguments sent to POST /message, for inspection on hover.
                      <Tooltip title={entry.message ?? 'No details captured'} arrow>
                        <span>{entry.toolName}</span>
                      </Tooltip>
                    ) : (
                      '—'
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={entry.statusCode ?? '—'}
                      color={entry.statusCode != null && entry.statusCode < 400 ? 'success' : 'error'}
                      size="small"
                    />
                  </TableCell>
                  <TableCell>
                    {entry.decisionEffect ? (
                      <Chip
                        label={entry.decisionEffect}
                        size="small"
                        color={
                          entry.decisionEffect === 'ALLOW'
                            ? 'success'
                            : entry.decisionEffect === 'DENY'
                              ? 'error'
                              : 'warning'
                        }
                      />
                    ) : (
                      '—'
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}
