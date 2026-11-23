-- Parked cases (ADR 0008): a case the model or a server could not serve waits as AWAITING_RETRY and is replayed from
-- the start (task 2.10). It is stored before its investigation ended, so it has no outcome yet; it keeps the
-- disputes.opened event it came from, to be replayed with.

ALTER TABLE cases DROP CONSTRAINT cases_state_check;
ALTER TABLE cases ADD CONSTRAINT cases_state_check CHECK (state IN (
    'AWAITING_RETRY', 'DECIDED', 'ESCALATED', 'AWAITING_APPROVAL', 'APPROVED', 'REJECTED', 'ACTED', 'ACTION_FAILED'));

ALTER TABLE cases ALTER COLUMN outcome_event DROP NOT NULL;
ALTER TABLE cases ALTER COLUMN decided_at DROP NOT NULL;
ALTER TABLE cases ADD COLUMN opened_event TEXT;

-- V2's two approval constraints listed the states allowed to lack a field, so every new state was wrongly required to
-- have an approval request and a reviewer. They now name the states that need the field: adding a state cannot break
-- them again.
ALTER TABLE cases DROP CONSTRAINT cases_waiting_has_request;
ALTER TABLE cases ADD CONSTRAINT cases_waiting_has_request
    CHECK (state NOT IN ('AWAITING_APPROVAL', 'APPROVED', 'REJECTED', 'ACTED', 'ACTION_FAILED')
           OR approval_requested_at IS NOT NULL);
ALTER TABLE cases DROP CONSTRAINT cases_reviewed_has_reviewer;
ALTER TABLE cases ADD CONSTRAINT cases_reviewed_has_reviewer
    CHECK (state NOT IN ('APPROVED', 'REJECTED', 'ACTED', 'ACTION_FAILED')
           OR (reviewer IS NOT NULL AND reviewed_at IS NOT NULL));

-- A parked case has its event and no outcome; every other case has its outcome.
ALTER TABLE cases ADD CONSTRAINT cases_parked_has_event
    CHECK (state <> 'AWAITING_RETRY' OR (opened_event IS NOT NULL AND outcome_event IS NULL));
ALTER TABLE cases ADD CONSTRAINT cases_outcome_unless_parked
    CHECK (state = 'AWAITING_RETRY' OR (outcome_event IS NOT NULL AND decided_at IS NOT NULL));

-- The replay sweep's queue (task 2.10).
CREATE INDEX cases_awaiting_retry ON cases (dispute_id) WHERE state = 'AWAITING_RETRY';
