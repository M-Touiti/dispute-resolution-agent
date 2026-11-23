package dev.disputeagent.investigator.application.port.store;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cases, from the end of their investigation on. The first save writes the case with the investigation's outcome;
 * every later change (review, action) is an update guarded by the state the caller read, so two reviewers cannot both
 * act on one case.
 */
public interface CaseStore {

    /**
     * A stored case: as it is now, its investigation outcome as published (null while it is parked), and the signed
     * approval token if any.
     */
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

    /** A parked case with the event it came from, and when it may be replayed. */
    record ParkedCase(DisputeCase disputeCase, DisputeOpened event, Instant nextAttemptAt) {
    }

    /**
     * Stores a case parked before its investigation ended ({@code AWAITING_RETRY}, ADR 0008) with the event it came
     * from, so it can be replayed from the start, not before {@code nextAttemptAt}. No outcome and no event yet.
     */
    void park(DisputeCase parked, DisputeOpened event, Instant nextAttemptAt);

    /** Parked cases whose next attempt is due at {@code now}, oldest first, at most {@code limit}. */
    List<ParkedCase> dueForReplay(Instant now, int limit);

    /**
     * Sends a parked case's event to the replay topic through the outbox, and holds the case until {@code holdUntil}
     * so the next sweep does not send it again, in one transaction.
     *
     * @return false when the case is no longer parked or no longer due (another sweep took it)
     */
    boolean scheduleReplay(CaseId disputeId, DisputeOpened event, Instant now, Instant holdUntil);

    /** A replay that parked again: its history and replay count, due again at {@code nextAttemptAt}. */
    boolean repark(DisputeCase parked, Instant nextAttemptAt);

    /**
     * A parked case whose replay reached an outcome (or that escalated at the replay cap): written as a finished case
     * with its outcome and events, in one transaction. False when the case is no longer parked.
     */
    boolean complete(DisputeCase finished, CaseOutcome outcome, List<OutgoingEvent> events);

    /**
     * Writes the case's new state, history, review, token and action result, if it is still in {@code expected}.
     *
     * @param approvalToken the signed token to keep with the case (the approval); null keeps the stored one
     * @return false when the stored case is no longer in {@code expected}: someone else changed it first
     */
    boolean update(DisputeCase disputeCase, CaseState expected, String approvalToken);
}
