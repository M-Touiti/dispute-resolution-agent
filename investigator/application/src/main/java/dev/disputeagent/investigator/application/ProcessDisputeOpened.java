package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.application.replay.ReplayPolicy;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code disputes.opened} or {@code disputes.replay} event, idempotent on the dispute id. The caller acknowledges
 * the event only after this returns, that is once the case and its events are committed; publishing happens
 * afterwards, from the outbox (ADR 0008), off the consumer's path.
 *
 * <ol>
 *   <li>A case already stored is not investigated again by {@code disputes.opened}, and nothing is emitted again: its
 *       events were committed with it and the outbox delivers them.</li>
 *   <li>Otherwise the dispute is triaged and investigated. A decided case's action goes before a person
 *       ({@code AWAITING_APPROVAL}). The case is saved with {@code disputes.decided}, and
 *       {@code disputes.approval-requested} when there is an action, in one transaction.</li>
 *   <li>A case the model or a server could not serve is parked ({@code AWAITING_RETRY}): it is saved with its
 *       {@code disputes.opened} event and a time for its first replay, and nothing is emitted yet.</li>
 *   <li>{@code disputes.replay} re-runs a parked case from the start: an outcome completes it as above; parking again
 *       waits longer. A replay for a case that is no longer parked is a duplicate and is ignored.</li>
 * </ol>
 */
public final class ProcessDisputeOpened {

    private final HandleDisputeOpened handle;
    private final RequestApproval requestApproval;
    private final CaseStore cases;
    private final ReplayPolicy replays;
    private final Clock clock;

    public ProcessDisputeOpened(HandleDisputeOpened handle, RequestApproval requestApproval, CaseStore cases,
            ReplayPolicy replays, Clock clock) {
        this.handle = handle;
        this.requestApproval = requestApproval;
        this.cases = cases;
        this.replays = replays;
        this.clock = clock;
    }

    /** {@code disputes.opened}. Returns the outcome, and whether it came from a case stored earlier. */
    public Result process(DisputeOpened dispute) {
        var existing = cases.find(dispute.disputeId());
        if (existing.isPresent()) {
            return new Result(existing.get().disputeCase().state(), existing.get().outcome(), true);
        }
        var investigated = handle.handle(dispute);
        if (investigated.state() == CaseState.AWAITING_RETRY) {
            cases.park(investigated, dispute, clock.instant().plus(replays.delayAfter(0)));
            return new Result(investigated.state(), null, false);
        }
        CaseOutcome outcome = CaseOutcome.of(investigated);
        cases.save(investigated, outcome, events(investigated, outcome));
        return new Result(investigated.state(), outcome, false);
    }

    /** {@code disputes.replay}: a parked case, from the start. */
    public Result processReplay(DisputeOpened dispute) {
        var existing = cases.find(dispute.disputeId());
        if (existing.isEmpty() || existing.get().disputeCase().state() != CaseState.AWAITING_RETRY) {
            return new Result(existing.map(s -> s.disputeCase().state()).orElse(null),
                    existing.map(CaseStore.StoredCase::outcome).orElse(null), true);
        }
        var replayed = handle.resume(existing.get().disputeCase(), dispute);
        if (replayed.state() == CaseState.AWAITING_RETRY) {
            cases.repark(replayed, clock.instant().plus(replays.delayAfter(replayed.replays())));
            return new Result(replayed.state(), null, false);
        }
        CaseOutcome outcome = CaseOutcome.of(replayed);
        cases.complete(replayed, outcome, events(replayed, outcome));
        return new Result(replayed.state(), outcome, false);
    }

    private List<OutgoingEvent> events(DisputeCase investigated, CaseOutcome outcome) {
        List<OutgoingEvent> events = new ArrayList<>();
        events.add(new OutgoingEvent.Decided(outcome));
        requestApproval.request(investigated).ifPresent(r -> events.add(new OutgoingEvent.ApprovalRequested(r)));
        return events;
    }

    /**
     * @param state   where the case is now (null for a replay of an unknown case)
     * @param outcome the investigation's outcome; null while the case is parked
     */
    public record Result(CaseState state, CaseOutcome outcome, boolean redelivery) {
    }
}
