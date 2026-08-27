import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'

interface Props {
  title: string
  loading: boolean
  forbidden: boolean
  error: string | null
  children: ReactNode
}

/**
 * Shared card frame for every audience panel (Stage 29, ADR-029) — one place
 * that decides how loading, "your role isn't granted this" and a genuine
 * failure each look, so the four panels can't drift apart.
 */
export default function PanelFrame({ title, loading, forbidden, error, children }: Props) {
  return (
    <Card>
      <CardContent>
        <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 2 }}>
          {title}
        </Typography>
        {loading ? (
          <Box sx={{ display: 'grid', placeItems: 'center', minHeight: 140 }}>
            <CircularProgress size={28} />
          </Box>
        ) : forbidden ? (
          <Alert severity="info">
            Your role isn’t granted this panel — the gateway refused it (403). That refusal is the
            control; this tab is only a convenience.
          </Alert>
        ) : error ? (
          <Alert severity="error">{error}</Alert>
        ) : (
          children
        )}
      </CardContent>
    </Card>
  )
}
