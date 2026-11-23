-- The transactional outbox (ADR 0008). Every event the investigator emits is a row here, written in the transaction
-- that saves the case, so a case is never stored without its events and an event never leaves without its case. A
-- relay publishes unsent rows in id order and marks them sent; delivery is at least once and consumers are
-- idempotent on the dispute id. Sent rows are kept as a record of what left; the partial index keeps the relay's
-- scan to the unsent ones.
CREATE TABLE outbox (
    id          BIGSERIAL PRIMARY KEY,
    dispute_id  TEXT        NOT NULL,
    type        TEXT        NOT NULL CHECK (type IN ('DECIDED', 'APPROVAL_REQUESTED')),
    payload     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT
);
CREATE INDEX outbox_unsent ON outbox (id) WHERE sent_at IS NULL;
