package dev.disputeagent.investigator.application.port.events;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
import java.util.Objects;

/**
 * An event the investigator emits, keyed by dispute id. It is saved with the case, in the case's transaction, and
 * published from the outbox afterwards (ADR 0008): a case is never stored without its events, and an event never
 * leaves without its case. Delivery is at least once; consumers are idempotent on the dispute id.
 */
public sealed interface OutgoingEvent {

    CaseId disputeId();

    /** {@code disputes.decided}: the investigation's outcome. */
    record Decided(CaseOutcome outcome) implements OutgoingEvent {
        public Decided {
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public CaseId disputeId() {
            return outcome.disputeId();
        }
    }

    /** {@code disputes.approval-requested}: a decided case's action waits for a person (ADR 0005). */
    record ApprovalRequested(ApprovalRequest request) implements OutgoingEvent {
        public ApprovalRequested {
            Objects.requireNonNull(request, "request");
        }

        @Override
        public CaseId disputeId() {
            return request.caseId();
        }
    }
}
