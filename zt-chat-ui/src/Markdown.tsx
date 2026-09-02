import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Paper from '@mui/material/Paper'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'

/**
 * Renders the assistant's markdown (ADR-039).
 *
 * <p>The assistant answers CRM questions with tables — that is the natural shape for
 * "four deals, their amounts and their risk flags" — and a plain-text console turned
 * them into rows of pipes. The answers that read best were the ones that looked worst.
 *
 * <p>No raw HTML is enabled and none is wanted: this text is composed by a model out
 * of CRM records, i.e. from data this system deliberately treats as untrusted.
 * react-markdown escapes HTML by default and refuses `javascript:` URLs; links open
 * in a new tab with `noopener` so a rendered link cannot reach back into this page.
 */
export default function Markdown({ children }: { children: string }) {
  return (
    <Box
      sx={{
        '& > :first-of-type': { mt: 0 },
        '& > :last-child': { mb: 0 },
        '& p': { my: 1 },
        '& ul, & ol': { my: 1, pl: 3 },
        '& li': { mb: 0.5 },
      }}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => <Typography variant="body1" component="p">{children}</Typography>,
          li: ({ children }) => (
            <Typography variant="body1" component="li">{children}</Typography>
          ),
          h1: ({ children }) => <Typography variant="h6" sx={{ mt: 2, mb: 1 }}>{children}</Typography>,
          h2: ({ children }) => <Typography variant="subtitle1" sx={{ mt: 2, mb: 1, fontWeight: 600 }}>{children}</Typography>,
          h3: ({ children }) => <Typography variant="subtitle2" sx={{ mt: 2, mb: 1, fontWeight: 600 }}>{children}</Typography>,
          a: ({ href, children }) => (
            <Link href={href} target="_blank" rel="noopener noreferrer">{children}</Link>
          ),
          code: ({ children, className }) => {
            const isBlock = (className ?? '').startsWith('language-')
            return isBlock ? (
              <Box component="code" sx={{ display: 'block', fontFamily: 'monospace', fontSize: '0.82rem' }}>
                {children}
              </Box>
            ) : (
              <Box component="code" sx={{
                fontFamily: 'monospace', fontSize: '0.85rem',
                bgcolor: 'action.hover', px: 0.6, py: 0.15, borderRadius: 0.5,
              }}>
                {children}
              </Box>
            )
          },
          pre: ({ children }) => (
            <Box component="pre" sx={{
              my: 1, p: 1.25, bgcolor: 'action.hover', borderRadius: 1,
              overflowX: 'auto', fontSize: '0.82rem',
            }}>
              {children}
            </Box>
          ),
          blockquote: ({ children }) => (
            <Box sx={{ borderLeft: 3, borderColor: 'divider', pl: 1.5, my: 1, color: 'text.secondary' }}>
              {children}
            </Box>
          ),
          // A wide table must scroll inside its own box rather than stretching the
          // chat column — the trace panel next to it does not move.
          table: ({ children }) => (
            <TableContainer component={Paper} variant="outlined" sx={{ my: 1.5, overflowX: 'auto' }}>
              <Table size="small">{children}</Table>
            </TableContainer>
          ),
          thead: ({ children }) => <TableHead>{children}</TableHead>,
          tbody: ({ children }) => <TableBody>{children}</TableBody>,
          tr: ({ children }) => <TableRow>{children}</TableRow>,
          th: ({ children }) => (
            <TableCell sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{children}</TableCell>
          ),
          td: ({ children }) => <TableCell>{children}</TableCell>,
          hr: () => <Box sx={{ borderTop: 1, borderColor: 'divider', my: 1.5 }} />,
        }}
      >
        {children}
      </ReactMarkdown>
    </Box>
  )
}
