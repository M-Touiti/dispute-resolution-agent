-- Every action carried out, one row per approval token (ADR 0005). The token id is the primary key: a token is used
-- once, and the row is written in the same transaction as the action's effect, so a call either did everything or
-- nothing. A call with a token already used returns result, the stored answer of the first call, byte for byte.
--
-- In this deployment the row is the effect: there is no card system behind it. Provisional credits are summed per
-- dispute for the limit; a close_dispute row closes the dispute to any further action.
CREATE TABLE actions (
    token_id         UUID PRIMARY KEY,
    dispute_id       TEXT        NOT NULL,
    tool             TEXT        NOT NULL CHECK (tool IN ('issue_provisional_credit', 'request_merchant_evidence',
                                                          'close_dispute')),
    arguments        JSONB       NOT NULL,
    arguments_sha256 TEXT        NOT NULL,
    amount_minor     BIGINT      CHECK ((tool = 'issue_provisional_credit') = (amount_minor IS NOT NULL)
                                        AND (amount_minor IS NULL OR amount_minor > 0)),
    reviewer         TEXT        NOT NULL,
    key_id           TEXT        NOT NULL,
    result           TEXT        NOT NULL,
    executed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX actions_dispute ON actions (dispute_id, executed_at);
