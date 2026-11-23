-- The replay of parked cases (ADR 0008, task 2.10). A parked case is due for replay at next_attempt_at: after a delay
-- that doubles with each replay, and held while a replay is in flight. The sweep sends its disputes.opened event to
-- disputes.replay through the outbox, as a REPLAY row.

ALTER TABLE cases ADD COLUMN next_attempt_at TIMESTAMPTZ;
UPDATE cases SET next_attempt_at = now() WHERE state = 'AWAITING_RETRY';
ALTER TABLE cases ADD CONSTRAINT cases_parked_has_next_attempt
    CHECK (state <> 'AWAITING_RETRY' OR next_attempt_at IS NOT NULL);

DROP INDEX cases_awaiting_retry;
CREATE INDEX cases_due_for_replay ON cases (next_attempt_at) WHERE state = 'AWAITING_RETRY';

ALTER TABLE outbox DROP CONSTRAINT outbox_type_check;
ALTER TABLE outbox ADD CONSTRAINT outbox_type_check CHECK (type IN ('DECIDED', 'APPROVAL_REQUESTED', 'REPLAY'));
