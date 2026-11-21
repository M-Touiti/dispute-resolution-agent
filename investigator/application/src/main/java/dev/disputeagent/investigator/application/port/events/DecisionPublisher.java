package dev.disputeagent.investigator.application.port.events;

import dev.disputeagent.investigator.application.CaseOutcome;

/**
 * Publishes {@code disputes.decided}, keyed by dispute id. Returns when the broker has the event; throws otherwise.
 * Publishing the same outcome twice is allowed: consumers are idempotent on the dispute id. The outbox (M2) replaces
 * publish-after-commit.
 */
public interface DecisionPublisher {

    void publish(CaseOutcome outcome);
}
