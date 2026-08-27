import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { PendingApproval } from '../../types'

/**
 * The live hold queue, inline on the dashboard (Stage 29, ADR-029) — the one
 * place on this page where a viewer can act rather than read. Decisions go
 * through the same `/api/v1/approver/**` API as the Approval Center (ADR-026),
 * so an approval made here is identical in the audit trail.
 */
export default function ApprovalQueueCard({ accessToken }: { accessToken: string }) {
  const [items, setItems] = useState<PendingApproval[]>([])
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    const res = await fetch('/api/v1/approver/approvals', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    if (res.ok) setItems(await res.json())
  }, [accessToken])

  useEffect(() => {
    load()
    const timer = setInterval(load, 15_000)
    return () => clearInterval(timer)
  }, [load])

  const decide = async (approval: PendingApproval, action: 'approve' | 'reject') => {
    setBusyId(approval.id)
    try {
      await fetch(`/api/v1/approver/approvals/${approval.id}/${action}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      await load()
    } finally {
      setBusyId(null)
    }
  }

  return (
    <Box>
      <Typography variant="caption" color="text.secondary">APPROVAL QUEUE</Typography>
      {items.length === 0 ? (
        <Typography variant="body2" sx={{ mt: 1 }}>
          Nothing held right now.
        </Typography>
      ) : (
        <Stack spacing={1.25} sx={{ mt: 1 }}>
          {items.slice(0, 4).map((a) => (
            <Box
              key={a.id}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 2,
                p: 1.5,
                border: '1px solid',
                borderColor: 'warning.main',
                borderRadius: 2,
                bgcolor: 'rgba(245, 166, 35, 0.05)',
              }}
            >
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography variant="subtitle2" noWrap>
                  {a.displayIdentity ?? a.agentId} · {a.toolName}
                </Typography>
                <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block' }}>
                  {a.reason ?? 'held for human approval'}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" variant="contained" color="success" disabled={busyId === a.id}
                        onClick={() => decide(a, 'approve')}>
                  Allow
                </Button>
                <Button size="small" variant="outlined" color="error" disabled={busyId === a.id}
                        onClick={() => decide(a, 'reject')}>
                  Deny
                </Button>
              </Stack>
            </Box>
          ))}
        </Stack>
      )}
    </Box>
  )
}
