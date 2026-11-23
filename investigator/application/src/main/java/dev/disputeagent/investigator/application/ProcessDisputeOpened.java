package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code disputes.opened} event, idempotent on the dispute id. The caller acknowledges the event only after this
 * returns, that is once the case and its events are committed; publishing happens afterwards, from the outbox (ADR
 * 0008), off the consumer's path.
 *
 * <ol>
 *   <li>A case already stored is not investigated again and nothing is emitted again: its events were committed with
 *       it and the outbox delivers them.</li>
 *   <li>Otherwise the dispute is triaged and investigated. A decided case's action goes before a person
 *       ({@code AWAITING_APPROVAL}). The case is saved with {@code disputes.decided}, and
 *       {@code disputes.approval-requested} when there is an action, in one transaction. A crash before the commit
 *       re-runs the case on redelivery; after it, the redelivery finds the case.</li>
 *   <li>A case the model or a server could not serve is parked ({@code AWAITING_RETRY}, ADR 0008): it is saved with
 *       its {@code disputes.opened} event, to be replayed from the start, and nothing is emitted yet.</li>
 * </ol>
 */
public final class ProcessDisputeOpened {

    private final HandleDisputeOpened handle;
    private final RequestApproval requestApproval;
    private final CaseStore cases;

    public ProcessDisputeOpened(HandleDisputeOpened handle, RequestApproval requestApproval, CaseStore cases) {
        this.handle = handle;
        this.requestApproval = requestApproval;
        this.cases = cases;
    }

    /** Returns the outcome, and whether it came from a case stored earlier. */
    public Result process(DisputeOpened dispute) {
        var existing = cases.find(dispute.disputeId());
        if (existing.isPresent()) {
            return new Result(existing.get().disputeCase().state(), existing.get().outcome(), true);
        }
        var investigated = handle.handle(dispute);
        if (investigated.state() == CaseState.AWAITING_RETRY) {
            cases.park(investigated, dispute);
            return new Result(investigated.state(), null, false);
        }
        CaseOutcome outcome = CaseOutcome.of(investigated);
        List<OutgoingEvent> events = new ArrayList<>();
        events.add(new OutgoingEvent.Decided(outcome));
        requestApproval.request(investigated).ifPresent(r -> events.add(new OutgoingEvent.ApprovalRequested(r)));
        cases.save(investigated, outcome, events);
        return new Result(investigated.state(), outcome, false);
    }

    /**
     * @param state   where the case is now
     * @param outcome the investigation's outcome; null while the case is parked
     */
    public record Result(CaseState state, CaseOutcome outcome, boolean redelivery) {
    }
}
