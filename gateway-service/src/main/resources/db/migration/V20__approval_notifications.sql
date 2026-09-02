-- ============================================================
-- V20 — approval_notifications: who was told, through what, and whether it worked
-- ============================================================
-- ADR-035. Without this table "the approver was notified" is unfalsifiable, and a
-- webhook that quietly 500s looks exactly like one that delivered. A held call
-- that nobody answered is a governance question; the first thing anyone will ask
-- is whether the notification actually went out.
--
-- One row per delivery ATTEMPT, including the skipped ones: "no webhook is
-- configured" is itself an answer to "why did nobody hear about this".
-- ============================================================

CREATE TABLE approval_notifications (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id  UUID         NOT NULL REFERENCES pending_approvals(id) ON DELETE CASCADE,
    channel      VARCHAR(16)  NOT NULL CHECK (channel IN ('WEBHOOK')),
    audience     VARCHAR(128),
    recipients   TEXT,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SKIPPED')),
    detail       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_approval_notifications_approval ON approval_notifications (approval_id, created_at DESC);

COMMENT ON TABLE approval_notifications IS
    'Delivery attempts for held-call notifications (ADR-035) — recipients are usernames resolved from the audience URN at send time, never email addresses or any other contact detail.';
