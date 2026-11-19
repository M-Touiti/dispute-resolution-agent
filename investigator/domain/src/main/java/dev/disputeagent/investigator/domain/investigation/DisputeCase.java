package dev.disputeagent.investigator.domain.investigation;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One dispute through investigation. Only legal transitions are possible, each is recorded with its time and
 * reason, and the outcome is set exactly once. Time is passed in; the domain has no clock.
 */
public final class DisputeCase {

    /** One transition, for the audit log. */
    public record StateChange(CaseState from, CaseState to, Instant at, String note) {
    }

    private final CaseId id;
    private final DisputeCategory reportedCategory;
    private final List<StateChange> history = new ArrayList<>();
    private CaseState state = CaseState.RECEIVED;
    private DisputeCategory category;
    private Decision decision;
    private GuardResult guardResult;
    private EscalationReason escalationReason;

    public DisputeCase(CaseId id, DisputeCategory reportedCategory, Instant receivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.reportedCategory = Objects.requireNonNull(reportedCategory, "reportedCategory");
        this.category = reportedCategory;
        history.add(new StateChange(null, CaseState.RECEIVED, Objects.requireNonNull(receivedAt), "received"));
    }

    /** Triage settled the category; it may differ from the one on the event (both are kept). */
    public void triaged(DisputeCategory triagedCategory, Instant at) {
        Objects.requireNonNull(triagedCategory, "triagedCategory");
        String note = triagedCategory == reportedCategory
                ? "category " + triagedCategory
                : "triage says " + triagedCategory + ", event says " + reportedCategory;
        move(CaseState.TRIAGED, at, note);
        this.category = triagedCategory;
    }

    public void investigationStarted(Instant at) {
        move(CaseState.INVESTIGATING, at, "investigation started");
    }

    /**
     * Records the agent's decision and the guard's verdict. A guard failure escalates as {@link
     * EscalationReason#GUARD_FAILED}; an {@code ESCALATE} outcome escalates as {@link
     * EscalationReason#AGENT_DECISION}; any other passing decision ends the investigation as {@code DECIDED}.
     */
    public void decided(Decision decision, GuardResult guardResult, Instant at) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(guardResult, "guardResult");
        requireState(CaseState.INVESTIGATING);
        this.decision = decision;
        this.guardResult = guardResult;
        if (!guardResult.passed()) {
            escalate(EscalationReason.GUARD_FAILED, at, guardResult.violations().size() + " guard violations");
        } else if (decision.outcome() == DecisionOutcome.ESCALATE) {
            escalate(EscalationReason.AGENT_DECISION, at, "the agent escalated");
        } else {
            move(CaseState.DECIDED, at, decision.outcome().name());
        }
    }

    /** Ends the case with a person: from any non-terminal state. */
    public void escalate(EscalationReason reason, Instant at, String detail) {
        Objects.requireNonNull(reason, "reason");
        move(CaseState.ESCALATED, at, reason + ": " + detail);
        this.escalationReason = reason;
    }

    private void move(CaseState to, Instant at, String note) {
        Objects.requireNonNull(at, "at");
        if (!state.next().contains(to)) {
            throw new IllegalStateException("case " + id + ": " + state + " -> " + to + " is not allowed");
        }
        history.add(new StateChange(state, to, at, note));
        state = to;
    }

    private void requireState(CaseState expected) {
        if (state != expected) {
            throw new IllegalStateException("case " + id + " is " + state + ", expected " + expected);
        }
    }

    public CaseId id() {
        return id;
    }

    public CaseState state() {
        return state;
    }

    public DisputeCategory reportedCategory() {
        return reportedCategory;
    }

    /** The category the investigation uses: the triaged one once triage ran, the reported one before. */
    public DisputeCategory category() {
        return category;
    }

    public Optional<Decision> decision() {
        return Optional.ofNullable(decision);
    }

    public Optional<GuardResult> guardResult() {
        return Optional.ofNullable(guardResult);
    }

    public Optional<EscalationReason> escalationReason() {
        return Optional.ofNullable(escalationReason);
    }

    public List<StateChange> history() {
        return List.copyOf(history);
    }
}
