import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import AppBar from '@mui/material/AppBar'
import Toolbar from '@mui/material/Toolbar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import Alert from '@mui/material/Alert'
import Markdown from './Markdown'
import type { ChatReply, MyEvent, MySpend, Turn } from './types'

// The trace polls; the conversation does not. A decision can arrive without the
// person typing anything (an approval decided elsewhere, an agent's call under the
// same rules), and the panel is meant to show the system reacting, not just echo
// the last message.
const TRACE_POLL_MS = 3000

const DECISION_COLOR: Record<string, string> = {
  ALLOW: '#2e7d32',
  DENY: '#c62828',
  HOLD: '#ed6c02',
}

export default function App() {
  const auth = useAuth()

  if (auth.isLoading) {
    return <Centered><CircularProgress /></Centered>
  }
  if (auth.error) {
    return (
      <Centered>
        <Alert severity="error" sx={{ mb: 2 }}>{auth.error.message}</Alert>
        <Button variant="contained" onClick={() => auth.signinRedirect()}>Try again</Button>
      </Centered>
    )
  }
  if (!auth.isAuthenticated) {
    return (
      <Centered>
        <Typography variant="h4" sx={{ mb: 1 }}>ZTeasy Chat Console</Typography>
        <Typography color="text.secondary" sx={{ mb: 3, maxWidth: 460, textAlign: 'center' }}>
          A CRM assistant with the same tools an agent has — and the same gate in front of them.
        </Typography>
        <Button variant="contained" size="large" onClick={() => auth.signinRedirect()}>Sign in</Button>
      </Centered>
    )
  }

  return (
    <Console
      accessToken={auth.user?.access_token ?? ''}
      username={auth.user?.profile.preferred_username}
      onSignOut={() => auth.removeUser()}
    />
  )
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column',
               alignItems: 'center', justifyContent: 'center', p: 3 }}>
      {children}
    </Box>
  )
}

interface ConsoleProps {
  accessToken: string
  username?: string
  onSignOut: () => void
}

function Console({ accessToken, username, onSignOut }: ConsoleProps) {
  const [turns, setTurns] = useState<Turn[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [events, setEvents] = useState<MyEvent[]>([])
  const [spend, setSpend] = useState<MySpend | null>(null)
  const [traceError, setTraceError] = useState<string | null>(null)
  const bottom = useRef<HTMLDivElement>(null)

  const authHeader = { Authorization: `Bearer ${accessToken}` }

  const refreshTrace = useCallback(async () => {
    try {
      const [e, s] = await Promise.all([
        fetch('/api/v1/me/events?limit=40', { headers: authHeader }),
        fetch('/api/v1/me/spend?hours=24', { headers: authHeader }),
      ])
      if (!e.ok) throw new Error(`events: HTTP ${e.status}`)
      setEvents(await e.json())
      if (s.ok) setSpend(await s.json())
      setTraceError(null)
    } catch (err) {
      setTraceError(err instanceof Error ? err.message : String(err))
    }
  }, [accessToken])

  useEffect(() => {
    refreshTrace()
    const timer = setInterval(refreshTrace, TRACE_POLL_MS)
    return () => clearInterval(timer)
  }, [refreshTrace])

  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth' }) }, [turns])

  const send = async () => {
    const text = draft.trim()
    if (!text || sending) return
    const history: Turn[] = [...turns, { role: 'user', content: text }]
    setTurns([...history, { role: 'assistant', content: '', pending: true }])
    setDraft('')
    setSending(true)
    try {
      const res = await fetch('/api/v1/chat', {
        method: 'POST',
        headers: { ...authHeader, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          messages: history.map((t) => ({ role: t.role, content: t.content })),
        }),
      })
      const body = await res.json()
      if (!res.ok) {
        setTurns([...history, { role: 'assistant', content: '', error: body.error ?? `HTTP ${res.status}` }])
      } else {
        const reply = body as ChatReply
        setTurns([...history, { role: 'assistant', content: reply.reply, steps: reply.steps }])
      }
    } catch (err) {
      setTurns([...history, { role: 'assistant', content: '', error: err instanceof Error ? err.message : String(err) }])
    } finally {
      setSending(false)
      refreshTrace()
    }
  }

  return (
    <Box sx={{ height: '100vh', display: 'flex', flexDirection: 'column', bgcolor: 'background.default' }}>
      <AppBar position="static">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>ZTeasy Chat Console</Typography>
          {spend && (
            <Chip
              size="small"
              color="default"
              label={`${(spend.costMicros / 1_000_000).toFixed(4)} USD · ${spend.inputTokens + spend.outputTokens} tokens today`}
            />
          )}
          <Typography variant="body2">{username}</Typography>
          <Button color="inherit" onClick={onSignOut}>Sign out</Button>
        </Toolbar>
      </AppBar>

      <Box sx={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {/* Left: the conversation. */}
        <Box sx={{ flex: '1 1 60%', display: 'flex', flexDirection: 'column', minWidth: 0, p: 2, gap: 2 }}>
          <Box sx={{ flex: 1, overflowY: 'auto', pr: 1 }}>
            {turns.length === 0 && (
              <Typography color="text.secondary" sx={{ mt: 4, textAlign: 'center' }}>
                Ask for CRM data or an action. Some of it you are allowed to do; the panel on the
                right shows what the gateway decided either way.
              </Typography>
            )}
            <Stack spacing={2}>
              {turns.map((turn, i) => (
                <Paper
                  key={i}
                  variant="outlined"
                  sx={{
                    p: 1.5,
                    alignSelf: turn.role === 'user' ? 'flex-end' : 'flex-start',
                    maxWidth: '85%',
                    bgcolor: turn.role === 'user' ? 'action.hover' : 'background.paper',
                  }}
                >
                  {turn.pending ? (
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <CircularProgress size={16} />
                      <Typography variant="body2" color="text.secondary">thinking, and calling tools…</Typography>
                    </Stack>
                  ) : turn.error ? (
                    <Alert severity="error" sx={{ py: 0 }}>{turn.error}</Alert>
                  ) : (
                    turn.role === 'assistant'
                      ? <Markdown>{turn.content}</Markdown>
                      : <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>{turn.content}</Typography>
                  )}
                  {turn.steps && turn.steps.length > 0 && (
                    <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                      {turn.steps.map((step, j) => (
                        <Typography key={j} variant="caption" sx={{ fontFamily: 'monospace', color: 'text.secondary' }}>
                          {step.name}: {step.detail}
                        </Typography>
                      ))}
                    </Stack>
                  )}
                </Paper>
              ))}
            </Stack>
            <div ref={bottom} />
          </Box>

          <Stack direction="row" spacing={1}>
            <TextField
              fullWidth
              size="small"
              placeholder="e.g. show me our EMEA contacts"
              value={draft}
              disabled={sending}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
            />
            <Button variant="contained" onClick={send} disabled={sending || !draft.trim()}>Send</Button>
          </Stack>
        </Box>

        {/* Right: what the gateway decided about this person. */}
        <Box sx={{ flex: '1 1 40%', minWidth: 340, borderLeft: 1, borderColor: 'divider',
                   display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <Box sx={{ px: 2, py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
            <Typography variant="subtitle1">Your ZTeasy trace</Typography>
            <Typography variant="caption" color="text.secondary">
              Every decision the gateway made about you — yours only, scoped server-side.
            </Typography>
          </Box>
          <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
            {traceError && <Alert severity="warning" sx={{ mb: 2 }}>{traceError}</Alert>}
            {events.length === 0 && !traceError && (
              <Typography variant="body2" color="text.secondary">
                Nothing yet. Ask the assistant for something.
              </Typography>
            )}
            <Stack spacing={1}>
              {events.map((event, i) => (
                <Paper key={i} variant="outlined" sx={{ p: 1, borderLeftWidth: 4,
                        borderLeftColor: DECISION_COLOR[event.decision ?? ''] ?? 'divider' }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Chip
                      size="small"
                      label={event.decision ?? '—'}
                      sx={{ bgcolor: DECISION_COLOR[event.decision ?? ''] ?? 'grey.400', color: '#fff' }}
                    />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', flexGrow: 1 }}>
                      {event.tool ?? event.path}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(event.timestamp).toLocaleTimeString()}
                    </Typography>
                  </Stack>
                  {event.reason && (
                    <Typography variant="caption" color="text.secondary"
                                sx={{ display: 'block', mt: 0.5, wordBreak: 'break-word' }}>
                      {event.reason}
                    </Typography>
                  )}
                </Paper>
              ))}
            </Stack>
          </Box>
        </Box>
      </Box>
    </Box>
  )
}
