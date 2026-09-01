import { useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'

interface Props {
  open: boolean
  agentName: string
  /** Pre-filled with a year out — the common case is "reviewed, next check in a year". */
  defaultNextDue: string
  onConfirm: (nextDue: string, note: string) => void
  onCancel: () => void
}

/**
 * Re-authorization dialog (Stage 32, ADR-032). Deliberately asks for a note:
 * the history this writes is the compliance answer to "on what basis was
 * this agent allowed to keep running", and a date alone doesn't answer it.
 */
export default function AcapLifecycleDialog({ open, agentName, defaultNextDue, onConfirm, onCancel }: Props) {
  const [nextDue, setNextDue] = useState(defaultNextDue)
  const [note, setNote] = useState('')

  return (
    <Dialog open={open} onClose={onCancel} fullWidth maxWidth="sm">
      <DialogTitle>Re-authorize {agentName}</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2 }}>
          Records who reviewed this agent and until when its scope stands. While a
          re-authorization is overdue, every call this agent makes is held for a human decision.
        </DialogContentText>
        <Stack spacing={2}>
          <TextField
            label="Next review due"
            type="date"
            value={nextDue}
            onChange={(e) => setNextDue(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Note (what was reviewed)"
            multiline
            minRows={2}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. scope unchanged, owner confirmed, no new data sources"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button variant="contained" disabled={!nextDue} onClick={() => onConfirm(nextDue, note)}>
          Re-authorize
        </Button>
      </DialogActions>
    </Dialog>
  )
}
