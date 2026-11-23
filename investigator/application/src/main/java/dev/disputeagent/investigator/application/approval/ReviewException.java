package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;

/** A review that cannot apply. The reviewer API maps these to 404 and 409 (task 2.7). */
public abstract sealed class ReviewException extends RuntimeException
        permits ReviewException.NotFound, ReviewException.Conflict {

    ReviewException(String message) {
        super(message);
    }

    /** No stored case has this id. */
    public static final class NotFound extends ReviewException {
        public NotFound(CaseId id) {
            super("no case " + id);
        }
    }

    /** The case is not waiting for this review, or another reviewer changed it first. */
    public static final class Conflict extends ReviewException {
        private final CaseState state;

        public Conflict(CaseId id, CaseState state) {
            super("case " + id + " is " + state);
            this.state = state;
        }

        public CaseState state() {
            return state;
        }
    }
}
