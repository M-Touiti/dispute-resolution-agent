package dev.disputeagent.investigator.domain.investigation;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of a case in M1. M2 adds the approval and action states after {@code DECIDED} (ADR 0005); the terminal
 * states here stay terminal for the investigation.
 */
public enum CaseState {
    RECEIVED,
    TRIAGED,
    INVESTIGATING,
    DECIDED,
    ESCALATED;

    public boolean isTerminal() {
        return this == DECIDED || this == ESCALATED;
    }

    /** Legal next states. Escalation is possible from every non-terminal state. */
    public Set<CaseState> next() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(TRIAGED, ESCALATED);
            case TRIAGED -> EnumSet.of(INVESTIGATING, ESCALATED);
            case INVESTIGATING -> EnumSet.of(DECIDED, ESCALATED);
            case DECIDED, ESCALATED -> EnumSet.noneOf(CaseState.class);
        };
    }
}
