package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Clock;
import java.util.Optional;

/**
 * A decided case's action goes before a person (ADR 0005). Called before the case is first saved, so the stored case
 * is already waiting and the request is published with the decision. An escalated case has no action and is left
 * as it is.
 */
public final class RequestApproval {

    private final AuditLog audit;
    private final Clock clock;

    public RequestApproval(AuditLog audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    public Optional<ApprovalRequest> request(DisputeCase decided) {
        if (decided.state() != CaseState.DECIDED) {
            return Optional.empty();
        }
        int mark = Transitions.mark(decided);
        ApprovalRequest request = decided.requestApproval(clock.instant());
        audit.record(new AuditEvent.ApprovalRequested(decided.id(), request.requestedAt(),
                request.action().toolName()));
        Transitions.since(decided, mark).forEach(audit::record);
        return Optional.of(request);
    }
}
