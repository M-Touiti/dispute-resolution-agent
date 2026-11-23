package dev.disputeagent.investigator.application.port.events;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;

/**
 * Publishes the investigator's events, keyed by dispute id: {@code disputes.decided} and
 * {@code disputes.approval-requested}. Returns when the broker has the event; throws otherwise. Publishing the same
 * event twice is allowed: consumers are idempotent on the dispute id. The outbox (task 2.8) replaces
 * publish-after-commit.
 */
public interface DecisionPublisher {

    void publish(CaseOutcome outcome);

    /** A decided case's action waits for a person (ADR 0005). */
    void publishApprovalRequest(ApprovalRequest request);
}
