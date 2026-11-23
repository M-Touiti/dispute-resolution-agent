package dev.disputeagent.investigator.application.port.store;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.util.List;
import java.util.Optional;

/**
 * Cases, from the end of their investigation on. The first save writes the case with the investigation's outcome;
 * every later change (review, action) is an update guarded by the state the caller read, so two reviewers cannot both
 * act on one case.
 */
public interface CaseStore {

    /** A stored case: as it is now, its investigation outcome as published, and the signed approval token if any. */
    record StoredCase(DisputeCase disputeCase, CaseOutcome outcome, Optional<String> approvalToken) {
    }

    Optional<StoredCase> find(CaseId disputeId);

    /** The reviewer's queue: cases waiting for approval, oldest request first, at most {@code limit}. */
    List<StoredCase> awaitingApproval(int limit);

    /**
     * Stores a case whose investigation just ended, with its outcome and the events it emits, in one transaction: the
     * events go to the outbox and are published from there (ADR 0008).
     */
    void save(DisputeCase disputeCase, CaseOutcome outcome, List<OutgoingEvent> events);

    /**
     * Writes the case's new state, history, review, token and action result, if it is still in {@code expected}.
     *
     * @param approvalToken the signed token to keep with the case (the approval); null keeps the stored one
     * @return false when the stored case is no longer in {@code expected}: someone else changed it first
     */
    boolean update(DisputeCase disputeCase, CaseState expected, String approvalToken);
}
