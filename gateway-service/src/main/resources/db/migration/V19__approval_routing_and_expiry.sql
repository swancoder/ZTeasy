-- ============================================================
-- V19 — approval routing and expiry (Stage 34, ADR-034)
-- ============================================================
-- `route_to` has existed since V13 but was written NULL by every code path:
-- hold rules had nowhere to express an approver, so nothing ever populated it.
-- ADR-034 gives agentMcpToolHolds rules a `routeTo` field, which lands here.
--
-- `expires_at` closes the other half: an approval with no deadline is not
-- "waiting", it is lost. A sweeper moves PENDING rows past their deadline to
-- EXPIRED, which is an outcome the audit trail records like any other — the
-- row must never simply vanish from the queue.
-- ============================================================

ALTER TABLE pending_approvals ADD COLUMN expires_at TIMESTAMPTZ;

-- Rows raised before this migration have no deadline of their own; give them
-- one measured from when they were raised, so the sweeper treats old and new
-- alike instead of leaving a pool of immortal items behind.
UPDATE pending_approvals
   SET expires_at = requested_at + INTERVAL '24 hours'
 WHERE expires_at IS NULL;

ALTER TABLE pending_approvals DROP CONSTRAINT IF EXISTS pending_approvals_status_check;
ALTER TABLE pending_approvals ADD  CONSTRAINT pending_approvals_status_check
      CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'));

-- The sweeper's query: pending, past deadline. Small table, but this is the
-- one statement that runs on a timer forever.
CREATE INDEX idx_pending_approvals_expiry ON pending_approvals (status, expires_at);
