package dev.disputeagent.investigator.domain.investigation;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of a case. The investigation runs from {@code RECEIVED} to {@code DECIDED} or {@code ESCALATED}; a case
 * the model or the ledger cannot serve is parked as {@code AWAITING_RETRY} and replayed from {@code RECEIVED} (ADR
 * 0008). A decided case then waits for a person, and an approved action is carried out once (ADR 0005).
 *
 * <pre>
 * RECEIVED -> TRIAGED -> INVESTIGATING -> DECIDED -> AWAITING_APPROVAL -> APPROVED -> ACTED | ACTION_FAILED
 *     |          |            |                            \-> REJECTED
 *     +----------+------------+--> AWAITING_RETRY -> RECEIVED (replay)
 *     +----------+------------+--> ESCALATED            \-> ESCALATED (replay cap)
 * </pre>
 */
public enum CaseState {
    RECEIVED,
    TRIAGED,
    INVESTIGATING,
    /** Parked: the model or the ledger was unavailable; the case will be replayed from the start. */
    AWAITING_RETRY,
    DECIDED,
    ESCALATED,
    /** The decision proposes an action and waits for a person. */
    AWAITING_APPROVAL,
    /** A person approved the action and a token was issued; the action has not run yet. */
    APPROVED,
    REJECTED,
    /** {@code actions-mcp} carried the action out. */
    ACTED,
    /** {@code actions-mcp} refused or failed the action; a person takes over. */
    ACTION_FAILED;

    /** No further transition: the case is with a person or done. */
    public boolean isTerminal() {
        return next().isEmpty();
    }

    /** The investigation has its outcome: decided by the agent, or escalated to a person. */
    public boolean endsInvestigation() {
        return this == DECIDED || this == ESCALATED;
    }

    /** Legal next states. */
    public Set<CaseState> next() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(TRIAGED, AWAITING_RETRY, ESCALATED);
            case TRIAGED -> EnumSet.of(INVESTIGATING, AWAITING_RETRY, ESCALATED);
            case INVESTIGATING -> EnumSet.of(DECIDED, AWAITING_RETRY, ESCALATED);
            case AWAITING_RETRY -> EnumSet.of(RECEIVED, ESCALATED);
            case DECIDED -> EnumSet.of(AWAITING_APPROVAL);
            case AWAITING_APPROVAL -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(ACTED, ACTION_FAILED);
            case ESCALATED, REJECTED, ACTED, ACTION_FAILED -> EnumSet.noneOf(CaseState.class);
        };
    }
}
