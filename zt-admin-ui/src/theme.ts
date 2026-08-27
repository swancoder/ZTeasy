import { createTheme } from '@mui/material/styles'

// Shared visual language for both consoles (Stage 29, ADR-029): a light,
// card-based dashboard look — flat surfaces, hairline borders, generous
// radius, colour used only to carry meaning (green allow / amber hold /
// red deny), never as decoration.
//
// Deliberately a theme rather than per-component styling: every existing
// table, dialog and chip inherits it without being rewritten, and the two
// SPAs stay visually identical without sharing a build.
//
// Kept as a copy in each project, like types.ts and ConfirmDialog.tsx — the
// two SPAs are independent npm projects by design (ADR-026).
export const DECISION_COLORS = {
  allow: '#00b374',
  hold: '#f5a623',
  deny: '#f4364c',
} as const

const theme = createTheme({
  palette: {
    mode: 'light',
    background: { default: '#f5f6f8', paper: '#ffffff' },
    primary: { main: '#1f7ae0' },
    success: { main: DECISION_COLORS.allow },
    warning: { main: DECISION_COLORS.hold },
    error: { main: DECISION_COLORS.deny },
    text: { primary: '#111827', secondary: '#6b7280' },
    divider: '#e5e7eb',
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    h5: { fontWeight: 600, letterSpacing: '-0.2px' },
    h6: { fontWeight: 600, letterSpacing: '-0.2px' },
    subtitle2: { fontWeight: 600 },
    // KPI numbers: the one place where size is the message.
    h3: { fontWeight: 700, letterSpacing: '-1px' },
    body2: { color: '#6b7280' },
  },
  components: {
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: { border: '1px solid #e5e7eb', backgroundImage: 'none' },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: { root: { border: '1px solid #e5e7eb' } },
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: { borderBottom: '1px solid #e5e7eb', backgroundColor: '#ffffff' },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { textTransform: 'none', fontWeight: 600, borderRadius: 999 } },
    },
    MuiChip: {
      styleOverrides: { root: { fontWeight: 600, borderRadius: 8 } },
    },
    MuiTab: {
      styleOverrides: { root: { textTransform: 'none', fontWeight: 600, minHeight: 48 } },
    },
    MuiTableCell: {
      styleOverrides: {
        head: { fontWeight: 600, color: '#6b7280', borderBottomColor: '#e5e7eb' },
        root: { borderBottomColor: '#f1f2f4' },
      },
    },
    MuiAccordion: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: { border: '1px solid #e5e7eb', borderRadius: 12, '&:before': { display: 'none' } },
      },
    },
  },
})

export default theme
