import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'

interface Props {
  label: string
  value: string
  /** Small line under the number — context, not decoration. */
  hint?: string
  /** Colour of the number itself; meaning only (see theme.ts). */
  tone?: 'default' | 'success' | 'warning' | 'error'
  /** Greyed-out state for a figure this deployment doesn't measure. */
  muted?: boolean
}

const TONE_COLOR = {
  default: 'primary.main',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
} as const

// One KPI tile (Stage 29, ADR-029). Kept deliberately dumb: it renders what
// it is given, so "is this number real?" is answered where the number is
// computed, not here.
export default function KpiCard({ label, value, hint, tone = 'default', muted = false }: Props) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="body2" sx={{ mb: 1 }}>
          {label}
        </Typography>
        <Typography variant="h3" sx={{ color: muted ? 'text.disabled' : TONE_COLOR[tone], lineHeight: 1.1 }}>
          {value}
        </Typography>
        {hint && (
          <Box sx={{ mt: 1 }}>
            <Typography variant="caption" color="text.secondary">
              {hint}
            </Typography>
          </Box>
        )}
      </CardContent>
    </Card>
  )
}
