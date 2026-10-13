ALTER TABLE outbox_events
    DROP CONSTRAINT outbox_events_status_check,
    ADD CONSTRAINT outbox_events_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD')),
    ADD COLUMN last_error TEXT,
    ADD COLUMN locked_until TIMESTAMPTZ;

ALTER TABLE webhook_inbox
    DROP CONSTRAINT webhook_inbox_status_check,
    ADD CONSTRAINT webhook_inbox_status_check
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD')),
    ADD COLUMN last_error TEXT,
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX outbox_events_processing_lease_idx ON outbox_events (locked_until)
    WHERE status = 'PROCESSING';

DROP INDEX webhook_inbox_pending_idx;

CREATE INDEX webhook_inbox_claim_idx ON webhook_inbox (next_attempt_at, received_at)
    WHERE status IN ('RECEIVED', 'FAILED');

CREATE INDEX webhook_inbox_processing_lease_idx ON webhook_inbox (locked_until)
    WHERE status = 'PROCESSING';
