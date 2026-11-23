-- From M2 a case lives on after its investigation (ADR 0005): a decided case waits for a person, an approved action
-- runs once. The row is written when the investigation ends and then updated, each update guarded by the state the
-- writer read. outcome_event stays the investigation's disputes.decided payload, as published.

ALTER TABLE cases DROP CONSTRAINT cases_state_check;
ALTER TABLE cases ADD CONSTRAINT cases_state_check CHECK (state IN (
    'DECIDED', 'ESCALATED', 'AWAITING_APPROVAL', 'APPROVED', 'REJECTED', 'ACTED', 'ACTION_FAILED'));

ALTER TABLE cases
    ADD COLUMN replays                INT         NOT NULL DEFAULT 0,
    ADD COLUMN approval_requested_at  TIMESTAMPTZ,
    ADD COLUMN reviewer               TEXT,
    ADD COLUMN review_note            TEXT,
    ADD COLUMN reviewed_at            TIMESTAMPTZ,
    -- The approval token's claims; the token itself is kept so an interrupted call can be resumed with it.
    ADD COLUMN token_id               UUID UNIQUE,
    ADD COLUMN token_arguments_sha256 TEXT,
    ADD COLUMN token_issued_at        TIMESTAMPTZ,
    ADD COLUMN token_expires_at       TIMESTAMPTZ,
    ADD COLUMN approval_token         TEXT,
    -- What actions-mcp answered: its result when acted, its reason when the action failed.
    ADD COLUMN action_detail          TEXT,
    ADD COLUMN updated_at             TIMESTAMPTZ;

ALTER TABLE cases ADD CONSTRAINT cases_waiting_has_request
    CHECK (state IN ('DECIDED', 'ESCALATED') OR approval_requested_at IS NOT NULL);
ALTER TABLE cases ADD CONSTRAINT cases_reviewed_has_reviewer
    CHECK (state IN ('DECIDED', 'ESCALATED', 'AWAITING_APPROVAL') OR (reviewer IS NOT NULL AND reviewed_at IS NOT NULL));
ALTER TABLE cases ADD CONSTRAINT cases_approved_has_token
    CHECK (state NOT IN ('APPROVED', 'ACTED', 'ACTION_FAILED')
           OR (token_id IS NOT NULL AND approval_token IS NOT NULL AND token_expires_at IS NOT NULL));

-- The reviewer's queue.
CREATE INDEX cases_awaiting_approval ON cases (approval_requested_at) WHERE state = 'AWAITING_APPROVAL';
