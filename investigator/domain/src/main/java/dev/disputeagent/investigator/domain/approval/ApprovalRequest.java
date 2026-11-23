package dev.disputeagent.investigator.domain.approval;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.time.Instant;
import java.util.Objects;

/** A decided case's proposed action, waiting for a person (ADR 0005). */
public record ApprovalRequest(CaseId caseId, ProposedAction action, Instant requestedAt) {

    public ApprovalRequest {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
