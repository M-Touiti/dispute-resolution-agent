package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import java.time.Clock;

/** A person rejects the proposed action: nothing is carried out, the case ends {@code REJECTED}. */
public final class RejectAction {

    private final CaseStore cases;
    private final AuditLog audit;
    private final Clock clock;

    public RejectAction(CaseStore cases, AuditLog audit, Clock clock) {
        this.cases = cases;
        this.audit = audit;
        this.clock = clock;
    }

    public ReviewResult reject(CaseId disputeId, String reviewer, String note) {
        var c = cases.find(disputeId).orElseThrow(() -> new ReviewException.NotFound(disputeId)).disputeCase();
        if (c.state() != CaseState.AWAITING_APPROVAL) {
            throw new ReviewException.Conflict(disputeId, c.state());
        }
        var review = new Review(reviewer, note, clock.instant());
        int mark = Transitions.mark(c);
        c.reject(review, review.at());
        if (!cases.update(c, CaseState.AWAITING_APPROVAL, null)) {
            throw new ReviewException.Conflict(disputeId, cases.find(disputeId).orElseThrow().disputeCase().state());
        }
        audit.record(new AuditEvent.Reviewed(disputeId, review.at(), false, reviewer, note,
                c.approvalRequest().orElseThrow().requestedAt()));
        Transitions.since(c, mark).forEach(audit::record);
        return new ReviewResult(disputeId, c.state(), note);
    }
}
