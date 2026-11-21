package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import java.util.Optional;

/**
 * One {@code disputes.opened} event, idempotent on the dispute id. The caller acknowledges the event only after this
 * returns.
 *
 * <ol>
 *   <li>A case already stored is not investigated again: its stored outcome is published again (a publish that failed
 *       after the save is completed by the redelivery) and the event is done.</li>
 *   <li>Otherwise the dispute is triaged and investigated, the finished case is saved, then the outcome is
 *       published. A crash before the save re-runs the case on redelivery; a crash after it only re-publishes.</li>
 * </ol>
 */
public final class ProcessDisputeOpened {

    private final HandleDisputeOpened handle;
    private final CaseStore cases;
    private final DecisionPublisher publisher;

    public ProcessDisputeOpened(HandleDisputeOpened handle, CaseStore cases, DecisionPublisher publisher) {
        this.handle = handle;
        this.cases = cases;
        this.publisher = publisher;
    }

    /** Returns the outcome, and whether it came from a case stored earlier. */
    public Result process(DisputeOpened dispute) {
        Optional<CaseOutcome> existing = cases.findOutcome(dispute.disputeId());
        if (existing.isPresent()) {
            publisher.publish(existing.get());
            return new Result(existing.get(), true);
        }
        var finished = handle.handle(dispute);
        CaseOutcome outcome = CaseOutcome.of(finished);
        cases.save(finished, outcome);
        publisher.publish(outcome);
        return new Result(outcome, false);
    }

    public record Result(CaseOutcome outcome, boolean redelivery) {
    }
}
