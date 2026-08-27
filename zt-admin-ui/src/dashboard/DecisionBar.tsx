import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { DECISION_COLORS } from '../theme'
import type { GateDecisions } from './types'

// The gate's allow/hold/deny split (Stage 29, ADR-029). A single stacked bar
// plus counts: the proportion is the point, and three separate numbers make
// the reader do the arithmetic themselves.
export default function DecisionBar({ decisions }: { decisions: GateDecisions }) {
  const { allowed, held, denied } = decisions
  const total = allowed + held + denied
  const pct = (n: number) => (total === 0 ? 0 : (n / total) * 100)

  return (
    <Box>
      <Box sx={{ display: 'flex', height: 10, borderRadius: 999, overflow: 'hidden', bgcolor: '#eef0f3', mb: 2 }}>
        <Box sx={{ width: `${pct(allowed)}%`, bgcolor: DECISION_COLORS.allow }} />
        <Box sx={{ width: `${pct(held)}%`, bgcolor: DECISION_COLORS.hold }} />
        <Box sx={{ width: `${pct(denied)}%`, bgcolor: DECISION_COLORS.deny }} />
      </Box>
      <Stack direction="row" spacing={4}>
        {([
          ['Allowed', allowed, DECISION_COLORS.allow],
          ['Held', held, DECISION_COLORS.hold],
          ['Denied', denied, DECISION_COLORS.deny],
        ] as const).map(([label, value, color]) => (
          <Box key={label}>
            <Typography variant="h5" sx={{ color }}>
              {value.toLocaleString()}
            </Typography>
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color }} />
              <Typography variant="caption" color="text.secondary">{label}</Typography>
            </Stack>
          </Box>
        ))}
      </Stack>
      {total === 0 && (
        <Typography variant="caption" color="text.secondary">
          No agent calls in this window yet.
        </Typography>
      )}
    </Box>
  )
}
