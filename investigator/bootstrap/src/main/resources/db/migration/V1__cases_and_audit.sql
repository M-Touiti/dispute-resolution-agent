-- The investigator's own schema: finished cases and the audit trail. The investigator role owns it and has no
-- access to the ledger, intake or policy schemas (deploy/compose/postgres/initdb/40-investigator.sh).

-- One row per finished case, written once. outcome_event is the exact disputes.decided payload (text, not jsonb,
-- so a redelivery publishes the same bytes).
CREATE TABLE cases (
    dispute_id        TEXT PRIMARY KEY,
    state             TEXT        NOT NULL CHECK (state IN ('DECIDED', 'ESCALATED')),
    reported_category TEXT        NOT NULL,
    category          TEXT        NOT NULL,
    outcome           TEXT,
    escalation_reason TEXT,
    unsafe_proposal   BOOLEAN     NOT NULL,
    decided_at        TIMESTAMPTZ NOT NULL,
    outcome_event     TEXT        NOT NULL,
    history           JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state = 'ESCALATED') = (escalation_reason IS NOT NULL))
);

-- Every model call, tool call, state change and decision, appended as it happens. Tool arguments are a SHA-256 in
-- the payload, never in clear.
CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    dispute_id  TEXT        NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    type        TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_dispute ON audit_events (dispute_id, id);
