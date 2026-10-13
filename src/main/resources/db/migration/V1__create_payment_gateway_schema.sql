CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants (id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'FAILED')),
    bank_reference VARCHAR(160) UNIQUE,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX payments_merchant_created_idx ON payments (merchant_id, created_at DESC);

CREATE TABLE idempotency_keys (
    merchant_id UUID NOT NULL REFERENCES merchants (id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    payment_id UUID REFERENCES payments (id),
    response_status SMALLINT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (merchant_id, idempotency_key)
);

CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'SUCCEEDED', 'FAILED')),
    request_metadata JSONB,
    response_metadata JSONB,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE (payment_id, attempt_number)
);

CREATE TABLE webhook_inbox (
    id UUID PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    payment_id UUID REFERENCES payments (id),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    UNIQUE (provider, external_event_id)
);

CREATE INDEX webhook_inbox_pending_idx ON webhook_inbox (received_at)
    WHERE status IN ('RECEIVED', 'FAILED');

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
