package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import java.util.Optional;

/**
 * One {@code disputes.opened} event, idempotent on the dispute id. The caller acknowledges the event only after this
 * returns.
 *
 * <ol>
 *   <li>A case already stored is not investigated again: its stored outcome, and its approval request while it is
 *       still waiting for one (not once a person has reviewed it), are published again (a publish that failed after the save is completed by the redelivery) and the event is
 *       done.</li>
 *   <li>Otherwise the dispute is triaged and investigated. A decided case's action goes before a person
 *       ({@code AWAITING_APPROVAL}); the case is saved, then the outcome and the approval request are published. A
 *       crash before the save re-runs the case on redelivery; a crash after it only re-publishes.</li>
 * </ol>
 */
public final class ProcessDisputeOpened {

    private final HandleDisputeOpened handle;
    private final RequestApproval requestApproval;
    private final CaseStore cases;
    private final DecisionPublisher publisher;

    public ProcessDisputeOpened(HandleDisputeOpened handle, RequestApproval requestApproval, CaseStore cases,
            DecisionPublisher publisher) {
        this.handle = handle;
        this.requestApproval = requestApproval;
        this.cases = cases;
        this.publisher = publisher;
    }

    /** Returns the outcome, and whether it came from a case stored earlier. */
    public Result process(DisputeOpened dispute) {
        var existing = cases.find(dispute.disputeId());
        if (existing.isPresent()) {
            CaseOutcome outcome = existing.get().outcome();
            publisher.publish(outcome);
            var stored = existing.get().disputeCase();
            if (stored.state() == CaseState.AWAITING_APPROVAL) {
                stored.approvalRequest().ifPresent(publisher::publishApprovalRequest);
            }
            return new Result(outcome, true);
        }
        var investigated = handle.handle(dispute);
        CaseOutcome outcome = CaseOutcome.of(investigated);
        Optional<ApprovalRequest> request = requestApproval.request(investigated);
        cases.save(investigated, outcome);
        publisher.publish(outcome);
        request.ifPresent(publisher::publishApprovalRequest);
        return new Result(outcome, false);
    }

    public record Result(CaseOutcome outcome, boolean redelivery) {
    }
}
