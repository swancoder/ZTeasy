import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { DailySpend } from './types'

interface Props {
  daily: DailySpend[]
  instrumented: boolean
}

// A plain inline-SVG area chart (Stage 29, ADR-029) rather than a charting
// dependency: one series, no interaction, and zt-admin-ui's bundle is already
// heavy from swagger-ui (SPECS §9). ~60 lines here beats another package.
export default function SpendChart({ daily, instrumented }: Props) {
  if (!instrumented || daily.length === 0) {
    return (
      <Box sx={{ height: 220, display: 'grid', placeItems: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No LLM spend reported yet — agents report usage to{' '}
          <code>/api/v1/internal/metering/llm</code>.
        </Typography>
      </Box>
    )
  }

  const W = 720
  const H = 200
  const PAD = { top: 16, right: 12, bottom: 24, left: 48 }
  const values = daily.map((d) => d.costMicros / 1_000_000)
  // Never scale to a zero maximum: a flat-zero series would divide by zero and
  // render as NaN paths.
  const max = Math.max(...values, 0.01)
  const stepX = (W - PAD.left - PAD.right) / Math.max(daily.length - 1, 1)
  const y = (v: number) => PAD.top + (H - PAD.top - PAD.bottom) * (1 - v / max)
  const x = (i: number) => PAD.left + i * stepX

  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const area = `${line} L${x(values.length - 1).toFixed(1)},${y(0).toFixed(1)} L${x(0).toFixed(1)},${y(0).toFixed(1)} Z`
  const last = values[values.length - 1]
  const gridValues = [max, max / 2, 0]

  return (
    <Box sx={{ width: '100%', overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={220} role="img"
           aria-label={`Daily LLM spend, latest €${last.toFixed(2)}`}>
        <defs>
          <linearGradient id="spendFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#1f7ae0" stopOpacity="0.22" />
            <stop offset="100%" stopColor="#1f7ae0" stopOpacity="0" />
          </linearGradient>
        </defs>
        {gridValues.map((v) => (
          <g key={v}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} stroke="#eef0f3" strokeWidth="1" />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#9ca3af">
              €{v.toFixed(v < 1 ? 2 : 0)}
            </text>
          </g>
        ))}
        <path d={area} fill="url(#spendFill)" />
        <path d={line} fill="none" stroke="#1f7ae0" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={x(values.length - 1)} cy={y(last)} r="4" fill="#1f7ae0" />
        <text x={PAD.left} y={H - 6} fontSize="11" fill="#9ca3af">{daily[0]?.date}</text>
        <text x={W - PAD.right} y={H - 6} fontSize="11" fill="#9ca3af" textAnchor="end">
          {daily[daily.length - 1]?.date}
        </text>
      </svg>
    </Box>
  )
}
