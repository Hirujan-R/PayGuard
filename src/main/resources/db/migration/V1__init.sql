-- PayGuard schema -------------------------------------------------------------

CREATE TABLE payment_transactions (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    account_id      VARCHAR(100) NOT NULL,
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)   NOT NULL,
    description     VARCHAR(500),
    ip_address      VARCHAR(45),
    region          VARCHAR(16),
    status          VARCHAR(32)  NOT NULL,
    fraud_score     DOUBLE PRECISION,
    fraud_reasons   VARCHAR(1000),
    bank_request_id VARCHAR(100),
    bank_reference  VARCHAR(100),
    failure_reason  VARCHAR(1000),
    refunded        BOOLEAN      NOT NULL DEFAULT FALSE,
    refunded_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_payment_account_time ON payment_transactions (account_id, created_at);
CREATE INDEX idx_payment_status_time   ON payment_transactions (status, updated_at);

-- The simulated bank keeps its OWN ledger. A charge is written here even when
-- the gateway never receives the response (the "lost response" failure mode),
-- which is what lets the reconciliation job resolve UNKNOWN transactions.
CREATE TABLE bank_ledger (
    id               UUID PRIMARY KEY,
    bank_request_id  VARCHAR(100) NOT NULL UNIQUE,
    account_id       VARCHAR(100) NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    region           VARCHAR(16),
    charge_reference VARCHAR(100) NOT NULL UNIQUE,
    processed_at     TIMESTAMPTZ  NOT NULL,
    refunded         BOOLEAN      NOT NULL DEFAULT FALSE,
    refunded_at      TIMESTAMPTZ
);

CREATE TABLE dead_letter_transactions (
    id            UUID PRIMARY KEY,
    transaction_id UUID NOT NULL UNIQUE REFERENCES payment_transactions (id),
    attempt_count INT  NOT NULL DEFAULT 0,
    last_error    VARCHAR(2000),
    status        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    replayed_at   TIMESTAMPTZ
);
