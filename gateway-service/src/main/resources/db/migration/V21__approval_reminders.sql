-- ============================================================
-- V21 — reminders before the deadline (Stage 36, ADR-036)
-- ============================================================
-- Both gateway apps run the scheduler (ADR-028: one image, two front doors).
-- Expiry survives that because sweeping CHANGES the approval's status, so the
-- second sweeper's query returns nothing. A reminder changes nothing about the
-- approval, so the same naive loop would send one message per instance, every
-- interval, until the deadline.
--
-- Hence claim-then-send: the row is inserted BEFORE the message goes out, and
-- this unique index makes the second instance lose the race with a duplicate-key
-- error instead of sending a duplicate message. The index is partial because a
-- RAISED notification may legitimately be attempted more than once.
-- ============================================================

ALTER TABLE approval_notifications ADD COLUMN kind  VARCHAR(16) NOT NULL DEFAULT 'RAISED';
ALTER TABLE approval_notifications ADD COLUMN stage VARCHAR(16);

ALTER TABLE approval_notifications DROP CONSTRAINT IF EXISTS approval_notifications_status_check;
ALTER TABLE approval_notifications ADD  CONSTRAINT approval_notifications_status_check
      CHECK (status IN ('CLAIMED', 'SENT', 'FAILED', 'SKIPPED'));

ALTER TABLE approval_notifications ADD CONSTRAINT approval_notifications_kind_check
      CHECK (kind IN ('RAISED', 'REMINDER'));

CREATE UNIQUE INDEX idx_approval_notifications_reminder_once
    ON approval_notifications (approval_id, stage)
 WHERE kind = 'REMINDER';

COMMENT ON COLUMN approval_notifications.stage IS
    'Which reminder this is, named by the configured fraction of the item lifetime that triggered it (e.g. 0.5). NULL for a RAISED notification.';
COMMENT ON COLUMN approval_notifications.status IS
    'CLAIMED means an instance won the right to send and the outcome is not recorded yet — a row left in this state is a crash between claim and send, not a delivery.';
